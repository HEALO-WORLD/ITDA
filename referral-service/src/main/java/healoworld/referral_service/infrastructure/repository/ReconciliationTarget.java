package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// 예약-슬롯 정합성 점검 대상 예약
// failureReason·confirmedAt: 장비 장애로 확정 뒤에 차단된 예약(기존 BOOKED 슬롯 유지 대상)을 구분하는 데 쓴다
public record ReconciliationTarget(UUID reservationId, UUID slotId, ReservationStatus reservationStatus,
                                   FailureReason failureReason, LocalDateTime confirmedAt) {

    // 실패 사유·확정 시각이 필요 없는 대상(진행 중 예약 등)
    public ReconciliationTarget(UUID reservationId, UUID slotId, ReservationStatus reservationStatus) {
        this(reservationId, slotId, reservationStatus, null, null);
    }
}
