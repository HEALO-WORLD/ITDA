package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ExaminationListResponse(

        // 검사 정보
        UUID examinationId,
        UUID referralId,
        UUID reservationId,
        ExaminationStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,

        // 의뢰 정보
        UUID examTypeId,

        // 예약 정보
        LocalDateTime examDate,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ReservationStatus reservationStatus,
        UUID equipmentId,

        // 검사 결과 정보
        boolean resultRegistered

) {

    public static ExaminationListResponse from(
            Examination examination,
            Referral referral,
            Reservation reservation,
            boolean resultRegistered
    ) {
        return new ExaminationListResponse(

                // 검사 정보
                examination.getId(),
                examination.getReferralId(),
                examination.getReservationId(),
                examination.getStatus(),
                examination.getStartedAt(),
                examination.getCompletedAt(),

                // 의뢰 정보
                referral.getExamTypeId(),

                // 예약 정보
                reservation.getExamDate(),
                reservation.getStartTime(),
                reservation.getEndTime(),
                reservation.getReservationStatus(),
                reservation.getEquipmentId(),

                // 검사 결과 정보
                resultRegistered
        );
    }
}