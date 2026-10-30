package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ReferralStatus;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

@Getter
@Builder
public class ReferralResponse {
    private UUID id;
    private UUID referringInstitutionId;
    private UUID referrerId;
    private UUID examTypeId;
    private UUID currentReservationId;
    private ReferralStatus referralStatus;
    private String patientName;

    public static ReferralResponse from(Referral referral) {
        return ReferralResponse.builder()
                .id(referral.getId())
                .referringInstitutionId(referral.getReferringInstitutionId())
                .referrerId(referral.getReferrerId())
                .examTypeId(referral.getExamTypeId())
                .currentReservationId(referral.getCurrentReservationId())
                .referralStatus(referral.getReferralStatus())
                .patientName(referral.getPatientName())
                .build();
    }
}
