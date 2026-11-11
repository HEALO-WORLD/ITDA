package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.ReconciliationFailureStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// p_reconciliation_failures: 예약 정합성 복구(ReservationReconciliationScheduler)가 실패한 예약의 기록
// - 예약당 1건(UNIQUE reservation_id). 같은 예약이 다시 실패하면 새 행 없이 횟수·마지막 실패 정보만 갱신한다
// - 저장은 ReconciliationFailureRepository.upsertFailure()(INSERT ... ON CONFLICT DO UPDATE)로만 하며,
//   복구 트랜잭션과 별도 트랜잭션(REQUIRES_NEW)이라 복구가 롤백돼도 기록은 남는다
// - status = MANUAL_REVIEW인 예약은 정합성 점검 대상 조회에서 제외된다 (예약·의뢰 상태는 바꾸지 않는다)
// - reservation_id는 같은 서비스 테이블이지만 이력 보존을 위해 FK 없이 ID로만 참조한다
@Getter
@Entity
@Table(
        name = "p_reconciliation_failures",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reconciliation_failures_reservation",
                columnNames = "reservation_id"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationFailure {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID reservationId;

    // 마지막으로 시도한 복구 이벤트 (예: SLOT_BOOKED)
    @Column(nullable = false, length = 50)
    private String resultType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReconciliationFailureStatus status;

    // 실패한 시도 횟수 (최초 실행 포함)
    @Column(nullable = false)
    private int failureCount;

    // 업무 예외면 ErrorCode 이름, 그 밖의 예외면 예외 클래스 이름
    @Column(nullable = false, length = 100)
    private String errorCode;

    // 실패 원인 요약 (환자 정보 없이 오류 메시지만, 500자 이내)
    @Column(nullable = false, length = 500)
    private String failureReason;

    @Column(nullable = false, updatable = false)
    private LocalDateTime firstFailedAt;

    @Column(nullable = false)
    private LocalDateTime lastFailedAt;
}
