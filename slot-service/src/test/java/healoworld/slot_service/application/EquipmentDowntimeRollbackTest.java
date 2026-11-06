package healoworld.slot_service.application;

import healoworld.slot_service.application.service.EquipmentDowntimeWriter;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// 등록 트랜잭션 중간(상태 이력)이나 마지막(Outbox) 저장이 DB 예외로 실패하면 중단 이력·슬롯 차단·이력·이벤트가 모두 롤백되는지 확인한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeRollbackTest extends PostgresContainerSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final UUID INSTITUTION_ID = UUID.randomUUID();

    @Autowired private EquipmentDowntimeWriter equipmentDowntimeWriter;
    @Autowired private SlotRepository slotRepository;
    @MockitoSpyBean private SlotStatusHistoryRepository slotStatusHistoryRepository;
    @Autowired private EquipmentDowntimeRepository equipmentDowntimeRepository;
    @MockitoSpyBean private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    @Test
    void 상태_이력_저장이_실패하면_중단_이력과_슬롯_차단이_모두_롤백된다() {
        UUID equipmentId = UUID.randomUUID();
        Slot first = slotRepository.saveAndFlush(Slot.create(equipmentId, UUID.randomUUID(), DAY,
                LocalTime.of(10, 0), LocalTime.of(10, 30)));
        Slot second = slotRepository.saveAndFlush(Slot.create(equipmentId, UUID.randomUUID(), DAY,
                LocalTime.of(10, 30), LocalTime.of(11, 0)));
        doThrow(new DataIntegrityViolationException("가상 DB 오류"))
                .when(slotStatusHistoryRepository).save(any());

        assertThatThrownBy(() -> equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID, DAY.atTime(9, 0), DAY.atTime(12, 0),
                "가상 사유", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(equipmentDowntimeRepository.count()).isZero();
        assertThat(slotStatusHistoryRepository.count()).isZero();
        for (Slot slot : new Slot[]{first, second}) {
            Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
            assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
            assertThat(saved.getEquipmentDowntimeId()).isNull();
        }
    }

    @Test
    void Outbox_저장이_실패하면_중단_이력_슬롯_차단_상태_이력이_모두_롤백된다() {
        UUID equipmentId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(Slot.create(equipmentId, UUID.randomUUID(), DAY,
                LocalTime.of(10, 0), LocalTime.of(10, 30)));
        doThrow(new DataIntegrityViolationException("가상 DB 오류"))
                .when(outboxEventRepository).save(any(OutboxEvent.class));

        assertThatThrownBy(() -> equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID, DAY.atTime(9, 0),
                DAY.atTime(12, 0), "가상 사유", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(equipmentDowntimeRepository.count()).isZero();
        assertThat(slotStatusHistoryRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getEquipmentDowntimeId()).isNull();
    }
}
