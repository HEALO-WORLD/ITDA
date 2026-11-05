package healoworld.slot_service.infrastructure;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeRepositoryTest extends PostgresContainerSupport {

    private static final LocalDateTime START = LocalDateTime.of(2026, 11, 10, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 11, 10, 12, 0);

    @Autowired
    private EquipmentDowntimeRepository equipmentDowntimeRepository;

    @AfterEach
    void tearDown() {
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    @Test
    void 구간이_겹치면_true이고_경계만_맞닿으면_false다() {
        UUID equipmentId = UUID.randomUUID();
        equipmentDowntimeRepository.saveAndFlush(EquipmentDowntime.create(equipmentId, START, END, "가상 사유"));

        // 안쪽, 앞쪽 걸침, 뒤쪽 걸침, 전체 포함
        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, START.plusHours(1), END.minusHours(1)))
                .isTrue();
        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, START.minusHours(1), START.plusMinutes(1)))
                .isTrue();
        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, END.minusMinutes(1), END.plusHours(1)))
                .isTrue();
        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, START.minusHours(1), END.plusHours(1)))
                .isTrue();

        // 중단 종료 = 슬롯 시작, 슬롯 종료 = 중단 시작
        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, END, END.plusHours(1))).isFalse();
        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, START.minusHours(1), START)).isFalse();
    }

    @Test
    void 다른_장비와_삭제된_중단은_겹침으로_보지_않는다() {
        UUID equipmentId = UUID.randomUUID();
        EquipmentDowntime deleted = EquipmentDowntime.create(equipmentId, START, END, "가상 사유");
        ReflectionTestUtils.setField(deleted, "deletedAt", FixedClockConfig.NOW);
        equipmentDowntimeRepository.saveAndFlush(deleted);
        equipmentDowntimeRepository.saveAndFlush(EquipmentDowntime.create(UUID.randomUUID(), START, END, "가상 사유"));

        assertThat(equipmentDowntimeRepository.existsActiveOverlap(equipmentId, START, END)).isFalse();
    }

    @Test
    void 시작이_종료보다_빠르지_않으면_엔티티를_만들_수_없다() {
        assertThatThrownBy(() -> EquipmentDowntime.create(UUID.randomUUID(), END, START, "가상 사유"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EquipmentDowntime.create(UUID.randomUUID(), START, START, "가상 사유"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
