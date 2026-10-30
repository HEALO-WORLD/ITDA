package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
public class ReservationResponse {
    private UUID id;
    private UUID referralId;
    private Integer attemptNo;
    private UUID slotId;
    private UUID examInstitutionId;
    private UUID equipmentId;
    private LocalDateTime examDate;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private ReservationStatus reservationStatus;
    private FailureReason failureReason;
    private LocalDateTime confirmedAt;
    private LocalDateTime endedAt;

    public static ReservationResponse from(Reservation reservation) {
        return ReservationResponse.builder()
                .id(reservation.getId())
                .referralId(reservation.getReferral().getId())
                .attemptNo(reservation.getAttemptNo())
                .slotId(reservation.getSlotId())
                .examInstitutionId(reservation.getExamInstitutionId())
                .equipmentId(reservation.getEquipmentId())
                .examDate(reservation.getExamDate())
                .startTime(reservation.getStartTime())
                .endTime(reservation.getEndTime())
                .reservationStatus(reservation.getReservationStatus())
                .failureReason(reservation.getFailureReason())
                .confirmedAt(reservation.getConfirmedAt())
                .endedAt(reservation.getEndedAt())
                .build();
    }
}
