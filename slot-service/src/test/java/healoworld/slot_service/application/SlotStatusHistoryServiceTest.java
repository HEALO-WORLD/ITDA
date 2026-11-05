package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.application.service.SlotStatusHistoryRecorder;
import healoworld.slot_service.application.service.SlotStatusHistoryService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.dto.PagedResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.presentation.dto.response.SlotStatusHistoryResponse;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotStatusHistoryServiceTest extends PostgresContainerSupport {

    private static final LocalDateTime NOW = FixedClockConfig.NOW;

    @Autowired
    private SlotStatusHistoryService slotStatusHistoryService;

    @Autowired
    private SlotStatusHistoryRecorder slotStatusHistoryRecorder;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @AfterEach
    void tearDown() {
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    // ---------- 조회 ----------

    @Test
    void 이력을_변경_시각_오래된_순으로_반환한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        UUID reservationId = UUID.randomUUID();
        // 저장 순서와 시각 순서를 일부러 다르게 둔다
        saveHistory(slot, reservationId, SlotStatus.HELD, SlotStatus.BOOKED, SlotStatusChangeReason.BOOK, NOW.plusMinutes(2));
        saveHistory(slot, reservationId, SlotStatus.AVAILABLE, SlotStatus.HELD, SlotStatusChangeReason.HOLD, NOW);
        saveHistory(slot, reservationId, SlotStatus.BOOKED, SlotStatus.AVAILABLE, SlotStatusChangeReason.RELEASE_SLOT, NOW.plusMinutes(5));

        PagedResponse<SlotStatusHistoryResponse> result = slotStatusHistoryService.getHistories(slot.getId(), 0, 20);

        assertThat(result.contents())
                .extracting(SlotStatusHistoryResponse::reason)
                .containsExactly(SlotStatusChangeReason.HOLD, SlotStatusChangeReason.BOOK, SlotStatusChangeReason.RELEASE_SLOT);
        assertThat(result.totalElements()).isEqualTo(3);

        SlotStatusHistoryResponse first = result.contents().getFirst();
        assertThat(first.slotId()).isEqualTo(slot.getId());
        assertThat(first.reservationId()).isEqualTo(reservationId);
        assertThat(first.fromStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(first.toStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(first.changedAt()).isEqualTo(NOW);
    }

    @Test
    void 다른_슬롯의_이력은_포함하지_않는다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        Slot other = slotRepository.saveAndFlush(SlotFixture.available());
        saveHistory(slot, UUID.randomUUID(), SlotStatus.AVAILABLE, SlotStatus.HELD, SlotStatusChangeReason.HOLD, NOW);
        saveHistory(other, UUID.randomUUID(), SlotStatus.AVAILABLE, SlotStatus.HELD, SlotStatusChangeReason.HOLD, NOW);

        PagedResponse<SlotStatusHistoryResponse> result = slotStatusHistoryService.getHistories(slot.getId(), 0, 20);

        assertThat(result.contents()).extracting(SlotStatusHistoryResponse::slotId).containsExactly(slot.getId());
    }

    @Test
    void 페이지_크기만큼_나눠_반환한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        for (int i = 0; i < 3; i++) {
            saveHistory(slot, UUID.randomUUID(), SlotStatus.AVAILABLE, SlotStatus.HELD,
                    SlotStatusChangeReason.HOLD, NOW.plusMinutes(i));
        }

        PagedResponse<SlotStatusHistoryResponse> second = slotStatusHistoryService.getHistories(slot.getId(), 1, 2);

        assertThat(second.contents()).extracting(SlotStatusHistoryResponse::changedAt).containsExactly(NOW.plusMinutes(2));
        assertThat(second.totalElements()).isEqualTo(3);
        assertThat(second.totalPages()).isEqualTo(2);
        assertThat(second.number()).isEqualTo(1);
        assertThat(second.size()).isEqualTo(2);
    }

    @Test
    void 이력이_없으면_빈_페이지를_반환한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());

        PagedResponse<SlotStatusHistoryResponse> result = slotStatusHistoryService.getHistories(slot.getId(), 0, 20);

        assertThat(result.contents()).isEmpty();
        assertThat(result.totalElements()).isZero();
    }

    @Test
    void 없는_슬롯이면_SLOT_NOT_FOUND() {
        assertThatThrownBy(() -> slotStatusHistoryService.getHistories(UUID.randomUUID(), 0, 20))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_NOT_FOUND);
    }

    @Test
    void 삭제된_슬롯이면_SLOT_NOT_FOUND() {
        Slot slot = SlotFixture.available();
        ReflectionTestUtils.setField(slot, "deletedAt", NOW.minusMinutes(1));
        slotRepository.saveAndFlush(slot);

        assertThatThrownBy(() -> slotStatusHistoryService.getHistories(slot.getId(), 0, 20))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_NOT_FOUND);
    }

    // ---------- 기록 ----------

    @Test
    void 기록은_트랜잭션_밖에서_호출하면_거부한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());

        // 상태 변경과 다른 트랜잭션에서 이력만 남는 일을 막기 위해 MANDATORY로 둔다
        assertThatThrownBy(() -> slotStatusHistoryRecorder.recordBySystem(slot.getId(), UUID.randomUUID(),
                SlotStatus.HELD, SlotStatus.AVAILABLE, SlotStatusChangeReason.HOLD_EXPIRED, NOW))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(slotStatusHistoryRepository.findAll()).isEmpty();
    }

    private void saveHistory(Slot slot, UUID reservationId, SlotStatus from, SlotStatus to,
                             SlotStatusChangeReason reason, LocalDateTime changedAt) {
        slotStatusHistoryRepository.saveAndFlush(
                SlotStatusHistory.ofSystem(slot, reservationId, from, to, reason, changedAt));
    }
}
