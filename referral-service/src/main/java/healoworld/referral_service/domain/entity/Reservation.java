package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.entity.BaseEntity;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_reservations")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SQLRestriction("deleted_at IS NULL")
public class Reservation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id", nullable = false)
    private Referral referral;

    @Column(nullable = false)
    private Integer attemptNo;

    @Column(nullable = false)
    private UUID slotId;

    @Column(nullable = false)
    private UUID examInstitutionId;

    @Column(nullable = false)
    private UUID equipmentId;

    @Column(nullable = false)
    private LocalDateTime examDate;

    @Column(nullable = false)
    private LocalDateTime startTime;

    @Column(nullable = false)
    private LocalDateTime endTime;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReservationStatus reservationStatus;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    private LocalDateTime confirmedAt;
    private LocalDateTime endedAt;

    public static Reservation create(Referral referral, Integer attemptNo, UUID slotId, UUID examInstitutionId, UUID equipmentId, LocalDateTime examDate, LocalDateTime startTime, LocalDateTime endTime) {
        return Reservation.builder()
                .referral(referral)
                .attemptNo(attemptNo)
                .slotId(slotId)
                .examInstitutionId(examInstitutionId)
                .equipmentId(equipmentId)
                .examDate(examDate)
                .startTime(startTime)
                .endTime(endTime)
                .reservationStatus(ReservationStatus.REQUESTED)
                .build();
    }


    // 상태 변경 hold ~ cancel
    public void hold() {
        validateReservationStatus(ReservationStatus.REQUESTED);
        this.reservationStatus = ReservationStatus.HELD;
    }

    public void confirm() {
        validateReservationStatus(ReservationStatus.HELD);
        this.reservationStatus = ReservationStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    public void fail(FailureReason failureReason) {
        switch (this.reservationStatus) {
            case REQUESTED -> validateFailureReason(failureReason,
                    FailureReason.SLOT_CONFLICT, FailureReason.EQUIPMENT_FAILURE);
            case HELD -> validateFailureReason(failureReason,
                    FailureReason.CONFIRM_FAILED, FailureReason.HOLD_EXPIRED, FailureReason.EQUIPMENT_FAILURE);
            default -> throw new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
        }
        this.reservationStatus = ReservationStatus.FAILED;
        this.failureReason = failureReason;
        this.endedAt = LocalDateTime.now();
    }

    public void block() {
        validateReservationStatus(ReservationStatus.CONFIRMED);
        this.reservationStatus = ReservationStatus.BLOCKED;
        this.failureReason = FailureReason.EQUIPMENT_FAILURE;
        this.endedAt = LocalDateTime.now();
    }

    public void cancel() {
        validateReservationStatus(ReservationStatus.CONFIRMED);
        this.reservationStatus = ReservationStatus.CANCELED;
        this.endedAt = LocalDateTime.now();
    }

    // soft delete
    public void delete(UUID userId) {
        validateReservationStatus(ReservationStatus.CANCELED, ReservationStatus.BLOCKED, ReservationStatus.FAILED);
        softDelete(userId);
    }

    private void validateReservationStatus(ReservationStatus... allowedStatuses) {
        for (ReservationStatus status : allowedStatuses) {
            if (this.reservationStatus == status) {
                return;
            }
        }
        throw new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
    }

    private void validateFailureReason(FailureReason failureReason, FailureReason... allowedReasons) {
        for (FailureReason reason : allowedReasons) {
            if (failureReason == reason) {
                return;
            }
        }
        throw new BaseException(ErrorCode.INVALID_FAILURE_REASON);
    }
}
