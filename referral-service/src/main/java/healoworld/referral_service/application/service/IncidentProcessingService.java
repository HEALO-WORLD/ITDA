package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.infrastructure.repository.IncidentAffectedReferralRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// 장비 장애 후속 처리 작업(IncidentReferral) 1건을 실행한다
// 영향 예약을 BLOCKED로, 연결된 의뢰를 NEEDS_RESCHEDULE로 바꾸고 작업에 성공·실패를 기록한다
@Slf4j
@Service
public class IncidentProcessingService {

    // p_incident_referrals.result_code (VARCHAR(20)) 값
    static final String RESCHEDULE_REQUIRED = "RESCHEDULE_REQUIRED";   // 예약 차단 + 의뢰 재조정 필요로 변경함
    static final String ALREADY_BLOCKED = "ALREADY_BLOCKED";           // 다른 장애·이전 시도가 이미 처리함 (변경 없음)
    static final String SKIPPED_NOT_CURRENT = "SKIPPED_NOT_CURRENT";   // 의뢰가 이미 다른 예약으로 재예약됨 (변경 없음)
    static final String SKIPPED_CANCELED = "SKIPPED_CANCELED";         // 의뢰 또는 예약이 이미 취소됨 (변경 없음)

    // 실패 원인 구분 (last_error 앞에 붙여 운영자가 종류를 알 수 있게 한다)
    static final String RETRYABLE = "[RETRYABLE]";                     // 잠시 뒤 다시 하면 처리될 수 있는 상태
    static final String DATA_INCONSISTENCY = "[DATA_INCONSISTENCY]";   // 의뢰·예약이 없거나 서로 연결이 맞지 않음
    static final String UNDECIDED_STATE = "[UNDECIDED_STATE]";         // 처리 정책이 정해지지 않은 상태 조합

    private static final int LAST_ERROR_MAX_LENGTH = 500;

    public enum Outcome {
        NOT_CLAIMED,   // 다른 작업자가 처리 중이거나 이미 끝난 작업이라 선점하지 못함 (아무것도 하지 않음)
        SUCCEEDED,     // 작업을 SUCCEEDED로 기록함 (result_code로 실제 처리 내용을 구분)
        FAILED,        // 작업을 FAILED로 기록함 (next_retry_at 이후 다시 선점 가능)
        LEASE_LOST     // 처리 도중 잠금이 다른 작업자에게 넘어가 내 변경을 모두 롤백함 (기록하지 않음)
    }

    private final IncidentReferralRepository incidentReferralRepository;
    private final IncidentAffectedReferralRepository incidentAffectedReferralRepository;
    private final IncidentReservationRepository incidentReservationRepository;

    // 항상 새 트랜잭션을 연다. 선점(T1)·처리(T2)·실패 기록(T3)을 서로 다른 트랜잭션으로 나누기 위해 쓴다
    private final TransactionTemplate newTransaction;
    private final Duration lockDuration;
    private final Duration retryDelay;

    public IncidentProcessingService(IncidentReferralRepository incidentReferralRepository,
                                     IncidentAffectedReferralRepository incidentAffectedReferralRepository,
                                     IncidentReservationRepository incidentReservationRepository,
                                     PlatformTransactionManager transactionManager,
                                     @Value("${itda.incident-processing.lock-duration:PT5M}") Duration lockDuration,
                                     @Value("${itda.incident-processing.retry-delay:PT1M}") Duration retryDelay) {
        this.incidentReferralRepository = incidentReferralRepository;
        this.incidentAffectedReferralRepository = incidentAffectedReferralRepository;
        this.incidentReservationRepository = incidentReservationRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lockDuration = lockDuration;
        this.retryDelay = retryDelay;
    }

    // 트랜잭션: 호출한 쪽 트랜잭션에 참여하지 않는다(NOT_SUPPORTED). DB 작업은 모두 newTransaction 안에서만 한다
    //  T1 선점: claim()을 독립 트랜잭션으로 커밋해 다른 작업자가 즉시 PROCESSING을 보게 한다. 0이면 아무것도 하지 않는다
    //  T2 처리: 의뢰 → 예약 순서로 FOR UPDATE 잠금 → 상태 판단 → block()/requestReschedule() → markSucceeded()까지 한 트랜잭션
    //          markSucceeded()가 0이면(잠금이 다른 작업자에게 넘어감) 예외로 T2 전체를 롤백해 예약·의뢰 변경도 취소한다
    //  T3 실패 기록: T2가 롤백된 뒤 새 트랜잭션에서 markFailed(). 내 잠금이 아니면 0이 되어 다른 작업자의 기록을 덮어쓰지 않는다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Outcome process(UUID incidentReferralId) {
        return process(incidentReferralId, LocalDateTime.now());
    }

    // 선점 기준 시각을 지정할 수 있는 실행 (잠금 만료 상황을 재현하는 테스트에서 사용)
    Outcome process(UUID incidentReferralId, LocalDateTime now) {
        // DB(TIMESTAMP)는 마이크로초까지만 저장하므로 fencing 비교에 쓰는 lockedUntil을 같은 정밀도로 맞춘다
        LocalDateTime claimedAt = now.truncatedTo(ChronoUnit.MICROS);
        LocalDateTime lockedUntil = claimedAt.plus(lockDuration).truncatedTo(ChronoUnit.MICROS);

        Integer claimed = newTransaction.execute(status ->
                incidentReferralRepository.claim(incidentReferralId, claimedAt, lockedUntil));
        if (claimed == null || claimed == 0) {
            return Outcome.NOT_CLAIMED;
        }

        try {
            newTransaction.executeWithoutResult(status -> handle(incidentReferralId, lockedUntil));
            return Outcome.SUCCEEDED;
        } catch (LeaseLostException e) {
            log.warn("장애 후속 처리 잠금 상실, 변경 롤백. incidentReferralId={}", incidentReferralId);
            return Outcome.LEASE_LOST;
        } catch (RuntimeException e) {
            return recordFailure(incidentReferralId, lockedUntil, e);
        }
    }

    // T2: 잠금 → 판단 → 변경 → 성공 기록
    private void handle(UUID incidentReferralId, LocalDateTime lockedUntil) {
        IncidentReferral task = incidentReferralRepository.findById(incidentReferralId)
                .orElseThrow(() -> new ProcessingFailure(DATA_INCONSISTENCY, "작업이 존재하지 않습니다."));
        UUID referralId = task.getReferralId();
        UUID reservationId = task.getReservationId();

        Referral referral = incidentAffectedReferralRepository.findByIdForUpdate(referralId)
                .orElseThrow(() -> new ProcessingFailure(DATA_INCONSISTENCY, "의뢰가 존재하지 않습니다. referralId=" + referralId));
        Reservation reservation = incidentReservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new ProcessingFailure(DATA_INCONSISTENCY, "예약이 존재하지 않습니다. reservationId=" + reservationId));

        String resultCode = decideAndApply(referral, reservation, referralId, reservationId);

        // 반드시 마지막에 호출한다. flushAutomatically로 위의 예약·의뢰 변경이 먼저 UPDATE되고,
        // clearAutomatically로 영속성 컨텍스트가 비워지므로 이후에는 referral·reservation 객체를 쓰지 않는다
        int updated = incidentReferralRepository.markSucceeded(incidentReferralId, lockedUntil, resultCode, now());
        if (updated == 0) {
            throw new LeaseLostException();
        }
    }

    // 잠금을 잡은 뒤의 실제 상태로 처리 방법을 정하고, 필요한 경우에만 도메인 메서드로 상태를 바꾼다
    private String decideAndApply(Referral referral, Reservation reservation, UUID referralId, UUID reservationId) {
        if (!reservation.getReferral().getId().equals(referralId)) {
            throw new ProcessingFailure(DATA_INCONSISTENCY, "예약이 작업의 의뢰에 속하지 않습니다. reservationId="
                    + reservationId + ", referralId=" + referralId);
        }
        if (referral.getCurrentReservationId() == null) {
            throw new ProcessingFailure(DATA_INCONSISTENCY, "의뢰에 현재 예약이 없습니다. referralId=" + referralId);
        }

        ReferralStatus referralStatus = referral.getReferralStatus();
        ReservationStatus reservationStatus = reservation.getReservationStatus();

        if (referralStatus == ReferralStatus.CANCELED) {
            return SKIPPED_CANCELED;
        }
        if (!referral.getCurrentReservationId().equals(reservationId)) {
            // 이미 다른 예약으로 재예약됐다. 이전 예약·새 예약 모두 건드리지 않는다
            return SKIPPED_NOT_CURRENT;
        }
        if (reservationStatus == ReservationStatus.CANCELED) {
            return SKIPPED_CANCELED;
        }
        if (referralStatus == ReferralStatus.CONFIRMED && reservationStatus == ReservationStatus.CONFIRMED) {
            reservation.block();
            referral.requestReschedule();
            return RESCHEDULE_REQUIRED;
        }
        if (referralStatus == ReferralStatus.NEEDS_RESCHEDULE && reservationStatus == ReservationStatus.BLOCKED) {
            return ALREADY_BLOCKED;
        }
        if (referralStatus == ReferralStatus.PENDING && reservationStatus == ReservationStatus.CONFIRMED) {
            // 예약 확정과 의뢰 확정 사이(Saga 진행 중)로 보고 상태를 바꾸지 않고 다시 시도한다
            throw new ProcessingFailure(RETRYABLE, "의뢰 확정 전 상태입니다. referral=" + referralStatus
                    + ", reservation=" + reservationStatus);
        }
        // TODO: NEEDS_RESCHEDULE+CONFIRMED, CONFIRMED+BLOCKED, FAILED 등은 처리 정책이 정해지지 않아 상태를 바꾸지 않는다
        throw new ProcessingFailure(UNDECIDED_STATE, "처리 정책이 없는 상태입니다. referral=" + referralStatus
                + ", reservation=" + reservationStatus);
    }

    // T3: 내 잠금일 때만 FAILED로 기록한다
    // TODO: 데이터 불일치·미정 상태도 지금은 재시도 대상이 된다. 최대 재시도 횟수 또는 영구 실패 표시 방법은 팀 결정 후 반영
    private Outcome recordFailure(UUID incidentReferralId, LocalDateTime lockedUntil, RuntimeException cause) {
        String lastError = truncate(describe(cause));
        LocalDateTime now = now();
        Integer updated = newTransaction.execute(status -> incidentReferralRepository.markFailed(
                incidentReferralId, lockedUntil, lastError, now.plus(retryDelay), now));
        if (updated == null || updated == 0) {
            log.warn("장애 후속 처리 실패 기록 생략(잠금 상실). incidentReferralId={}", incidentReferralId);
            return Outcome.LEASE_LOST;
        }
        log.warn("장애 후속 처리 실패. incidentReferralId={}, lastError={}", incidentReferralId, lastError);
        return Outcome.FAILED;
    }

    // last_error와 로그에 남길 실패 설명을 만든다
    // ProcessingFailure는 이 클래스가 만든 문구(분류 + ID·상태값)라 그대로 쓴다
    // 그 밖의 예외는 원본 메시지를 쓰지 않는다. PostgreSQL 제약 위반 메시지에는 "Failing row contains (...)"처럼
    // 행의 실제 컬럼 값(환자 이름 등)이 들어갈 수 있기 때문이다. 대신 예외 종류와 진단용 코드만 남긴다
    private static String describe(RuntimeException cause) {
        if (cause instanceof ProcessingFailure) {
            return cause.getMessage();
        }
        // 예상하지 못한 오류(DB 일시 오류 등)는 재시도 대상으로 기록한다
        StringBuilder description = new StringBuilder(RETRYABLE).append(' ').append(cause.getClass().getSimpleName());
        if (cause instanceof BaseException baseException) {
            // ErrorCode 이름은 고정된 상수라 개인정보가 들어가지 않는다
            description.append(" errorCode=").append(baseException.getErrorCode().name());
        }
        if (NestedExceptionUtils.getMostSpecificCause(cause) instanceof SQLException sqlException
                && sqlException.getSQLState() != null) {
            // SQLState는 오류 종류 코드(예: 23502 NOT NULL 위반)만 담는다
            description.append(" sqlState=").append(sqlException.getSQLState());
        }
        return description.toString();
    }

    // VARCHAR(500)은 문자 수 기준이므로 코드 포인트 단위로 자른다
    private static String truncate(String message) {
        if (message.codePointCount(0, message.length()) <= LAST_ERROR_MAX_LENGTH) {
            return message;
        }
        return message.substring(0, message.offsetByCodePoints(0, LAST_ERROR_MAX_LENGTH));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    // 처리할 수 없는 상태. T2를 롤백시키고 원인과 함께 FAILED로 기록된다
    static final class ProcessingFailure extends RuntimeException {
        ProcessingFailure(String kind, String message) {
            super(kind + " " + message);
        }
    }

    // markSucceeded()가 0건: 잠금이 다른 작업자에게 넘어갔다. T2를 롤백시키며 실패로 기록하지 않는다
    static final class LeaseLostException extends RuntimeException {
        LeaseLostException() {
            super("lease lost");
        }
    }
}
