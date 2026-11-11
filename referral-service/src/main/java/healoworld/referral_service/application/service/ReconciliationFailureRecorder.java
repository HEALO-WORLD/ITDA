package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.ReconciliationFailure;
import healoworld.referral_service.domain.enums.ReconciliationFailureStatus;
import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReconciliationFailureRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * 예약 정합성 복구의 결과(성공·실패·수동 확인 전환)를 기록한다.
 * <p>
 * - 모든 메서드는 REQUIRES_NEW라 복구 트랜잭션(ReservationReconciliationService.reconcile)이 롤백된 뒤에도 기록이 남는다
 * - 실패 분류: 다시 시도해도 결과가 바뀔 수 없는 업무 오류(NON_RECOVERABLE)만 즉시 MANUAL_REVIEW.
 *   그 밖의 업무 예외(동시 변경으로 인한 INVALID_REFERRAL_STATUS, 검사 동시 생성의 EXAMINATION_ALREADY_EXISTS 등)와
 *   일반 예외(DB 연결·잠금 대기 초과 등)는 상태가 바뀌면 성공할 수 있으므로 최초 실행 포함 maxAttempts회까지 RETRYING
 * - MANUAL_REVIEW 예약은 정합성 점검 대상 조회에서 빠진다. 예약·의뢰·검사·슬롯 상태는 바꾸지 않는다
 * - 메트릭 reservation.reconciliation.recovery{outcome=recovered|retry_scheduled|manual_review}
 */
@Slf4j
@Service
public class ReconciliationFailureRecorder {
    static final String METRIC = "reservation.reconciliation.recovery";
    private static final int REASON_MAX_LENGTH = 500;
    // 즉시 수동 확인으로 보내는 오류 코드: 검사가 이미 시작·완료돼 예약을 확정·재연결할 수 없는 경우
    // (검사 상태는 정합성 복구로 되돌릴 수 없어 재시도해도 같은 결과다)
    static final Set<ErrorCode> NON_RECOVERABLE = EnumSet.of(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);

    private final ReconciliationFailureRepository failureRepository;
    private final Clock clock;
    private final int maxAttempts;
    private final Counter recovered;
    private final Counter retryScheduled;
    private final Counter manualReview;

    public ReconciliationFailureRecorder(
            ReconciliationFailureRepository failureRepository,
            Clock clock,
            MeterRegistry meterRegistry,
            @Value("${itda.reservation-reconciliation.scheduler.max-attempts:3}") int maxAttempts
    ) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts는 1 이상이어야 합니다.");
        }
        this.failureRepository = failureRepository;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.recovered = counter(meterRegistry, "recovered");
        this.retryScheduled = counter(meterRegistry, "retry_scheduled");
        this.manualReview = counter(meterRegistry, "manual_review");
    }

    // 복구 성공: 앞선 일시 오류 기록(RETRYING)이 있으면 지운다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(UUID reservationId) {
        failureRepository.deleteRetryingByReservationId(reservationId);
        recovered.increment();
    }

    // 복구 실패: 예외를 분류해 기록하고, 기록된 처리 상태를 돌려준다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ReconciliationFailureStatus recordFailure(UUID reservationId, SlotResultType type, RuntimeException e) {
        boolean nonRecoverable = e instanceof BaseException base && NON_RECOVERABLE.contains(base.getErrorCode());
        String errorCode = e instanceof BaseException base ? base.getErrorCode().name() : e.getClass().getSimpleName();
        ReconciliationFailure failure = upsert(reservationId, type, nonRecoverable, errorCode, e.getMessage());
        ReconciliationFailureStatus status = failure.getStatus();
        int failureCount = failure.getFailureCount();

        if (status == ReconciliationFailureStatus.MANUAL_REVIEW) {
            manualReview.increment();
            // 수동 확인 전환은 한 번만 일어나므로 ERROR로 남긴다. 복구 불가 업무 오류는 원인이 errorCode로 충분해 스택을 생략한다
            if (nonRecoverable) {
                log.error("정합성 복구 실패: 복구 불가능한 업무 오류, 자동 재시도 중단(수동 확인 대상). reservationId={}, type={}, errorCode={}, failureCount={}",
                        reservationId, type, errorCode, failureCount);
            } else {
                log.error("정합성 복구 실패: 최대 시도 횟수({}) 도달, 자동 재시도 중단(수동 확인 대상). reservationId={}, type={}, errorCode={}, failureCount={}",
                        maxAttempts, reservationId, type, errorCode, failureCount, e);
            }
        } else {
            retryScheduled.increment();
            log.warn("정합성 복구 실패: 복구 가능 오류, 다음 회차 재시도. reservationId={}, type={}, errorCode={}, failureCount={}/{}",
                    reservationId, type, errorCode, failureCount, maxAttempts, e);
        }
        return status;
    }

    // 사전 확인에서 자동 복구하면 안 되는 예약을 찾은 경우: 이벤트를 주입하지 않고 바로 수동 확인 대상으로 기록한다
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordManualReview(UUID reservationId, SlotResultType type, String errorCode, String reason) {
        upsert(reservationId, type, true, errorCode, reason);
        manualReview.increment();
        log.error("정합성 복구 제외: 자동 복구 불가, 수동 확인 대상으로 기록. reservationId={}, type={}, errorCode={}, reason={}",
                reservationId, type, errorCode, reason);
    }

    private ReconciliationFailure upsert(UUID reservationId, SlotResultType type, boolean manual,
                                               String errorCode, String reason) {
        failureRepository.upsertFailure(UUID.randomUUID(), reservationId, type.name(), manual, maxAttempts,
                errorCode, truncate(reason), LocalDateTime.now(clock));
        return failureRepository.findByReservationId(reservationId).orElseThrow();
    }

    private static String truncate(String reason) {
        String value = (reason == null || reason.isBlank()) ? "(메시지 없음)" : reason;
        return value.length() <= REASON_MAX_LENGTH ? value : value.substring(0, REASON_MAX_LENGTH);
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder(METRIC)
                .description("예약 정합성 복구 결과")
                .tag("outcome", outcome)
                .register(registry);
    }
}
