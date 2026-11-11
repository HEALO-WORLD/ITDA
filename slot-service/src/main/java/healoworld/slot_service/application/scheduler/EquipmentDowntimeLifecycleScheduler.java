package healoworld.slot_service.application.scheduler;

import healoworld.slot_service.application.service.EquipmentDowntimeLifecycleWriter;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "equipment-downtime.lifecycle.enabled", havingValue = "true", matchIfMissing = true)
public class EquipmentDowntimeLifecycleScheduler {
    private final EquipmentDowntimeRepository downtimes;
    private final SlotRepository slots;
    private final EquipmentDowntimeLifecycleWriter writer;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${equipment-downtime.lifecycle.fixed-delay-ms:1000}")
    public void tick() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (UUID equipmentId : downtimes.findDueEquipmentIds(now, PageRequest.of(0, 100))) {
            try {
                writer.activateDue(equipmentId);
            } catch (RuntimeException e) {
                log.error("장비 중단 시작 반영 실패. equipmentId={}", equipmentId, e);
            }
        }
        // 복구 당시 아직 예약 연결이 남았던 슬롯도 보상 해제 후 다시 열 수 있게 한다.
        for (UUID equipmentId : slots.findRecoveryEquipmentIds(now.toLocalDate(), now.toLocalTime())) {
            try {
                writer.reopenRecovered(equipmentId);
            } catch (RuntimeException e) {
                log.error("복구된 장비의 슬롯 재개방 실패. equipmentId={}", equipmentId, e);
            }
        }
    }
}
