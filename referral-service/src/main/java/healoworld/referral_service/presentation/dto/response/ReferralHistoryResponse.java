package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class ReferralHistoryResponse {
    private UUID id;
    private UUID referralId;
    private UUID reservationId;
    private UUID eventId;
    private ReferralStatus fromStatus;
    private ReferralStatus toStatus;
    private Reason reason;
    private LocalDateTime occurredAt;
    private UUID changedBy;

    public static ReferralHistoryResponse from(ReferralHistory referralHistory) {
        return ReferralHistoryResponse.builder()
                .id(referralHistory.getId())
                .referralId(referralHistory.getReferral().getId())
                .reservationId(referralHistory.getReservation().getId())
                .eventId(referralHistory.getEventId())
                .fromStatus(referralHistory.getFromStatus())
                .toStatus(referralHistory.getToStatus())
                .reason(referralHistory.getReason())
                .occurredAt(referralHistory.getOccurredAt())
                .changedBy(referralHistory.getChangedBy())
                .build();
    }
}
