package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.global.entity.BaseEntity;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.util.UUID;

@Entity
@Table(name = "p_referrals")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SQLRestriction("deleted_at IS NULL")
public class Referral extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID referringInstitutionId;

    @Column(nullable = false, updatable = false)
    private UUID referrerId;

    @Column(nullable = false)
    private UUID examTypeId;

    private UUID currentReservationId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReferralStatus referralStatus;

    @Column(nullable = false, length = 20)
    private String patientName;

    @Column(nullable = false, unique = true)
    private UUID idempotencyKey;

    public static Referral create(UUID referringInstitutionId, UUID referrerId, UUID examTypeId, String patientName, UUID idempotencyKey) {

        return Referral.builder()
                .referringInstitutionId(referringInstitutionId)
                .referrerId(referrerId)
                .examTypeId(examTypeId)
                .referralStatus(ReferralStatus.PENDING)
                .patientName(patientName)
                .idempotencyKey(idempotencyKey)
                .build();
    }

    public void assignReservation(UUID reservationId) {
        validateReferralStatus(ReferralStatus.PENDING);
        if (this.currentReservationId != null) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);
        }
        this.currentReservationId = reservationId;
    }

    // 상태 변경 confirm ~ complete
    public void confirm() {
        validateReferralStatus(ReferralStatus.PENDING);
        this.referralStatus = ReferralStatus.CONFIRMED;
    }

    public void fail() {
        validateReferralStatus(ReferralStatus.PENDING);
        this.referralStatus = ReferralStatus.FAILED;
    }

    public void cancel() {
        validateReferralStatus(ReferralStatus.CONFIRMED, ReferralStatus.NEEDS_RESCHEDULE);
        this.referralStatus = ReferralStatus.CANCELED;
    }

    // 장비 장애 등으로 재조정이 필요할 때 변경
    public void requestReschedule() {
        validateReferralStatus(ReferralStatus.PENDING, ReferralStatus.CONFIRMED);
        this.referralStatus = ReferralStatus.NEEDS_RESCHEDULE;
    }

    // 새 예약으로 재조정을 요청할 때 변경
    public void reschedule(UUID newReservationId) {
        validateReferralStatus(ReferralStatus.NEEDS_RESCHEDULE);
        this.referralStatus = ReferralStatus.PENDING;
        this.currentReservationId = newReservationId;
    }

//    public void complete() {
//        validateReferralStatus(ReferralStatus.CONFIRMED);
//        this.referralStatus = ReferralStatus.COMPLETED;
//    }

    // 본인 확인
    public boolean isReferredBy(UUID userId) {
        return this.referrerId != null && this.referrerId.equals(userId);
    }

    // soft delete
    public void delete(UUID userId) {
        validateReferralStatus(ReferralStatus.FAILED, ReferralStatus.CANCELED);
        softDelete(userId);
    }

    private void validateReferralStatus(ReferralStatus... allowedStatuses) {
        for (ReferralStatus status : allowedStatuses) {
            if (this.referralStatus == status) {
                return;
            }
        }
        throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);
    }
}
