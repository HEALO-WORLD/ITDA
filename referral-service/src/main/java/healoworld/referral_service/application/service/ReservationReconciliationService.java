package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.domain.policy.SlotSnapshot;
import healoworld.referral_service.infrastructure.repository.ReconciliationTarget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ReservationReconciliationService {
    private final SlotResultService slotResultService;

    public void reconcile(ReconciliationTarget target, SlotResultType type, SlotSnapshot slotSnapshot) {
        UUID eventId = UUID.randomUUID();

        log.warn("정합성 복구 이벤트 주입. eventId={}, type={}, reservationId={}, reservationStatus={}, slotStatus={}", eventId, type, target.reservationId(), target.reservationStatus(), slotSnapshot.status());

        slotResultService.handle(eventId, type, target.reservationId());
    }
}
