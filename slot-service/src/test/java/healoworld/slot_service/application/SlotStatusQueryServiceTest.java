package healoworld.slot_service.application;

import healoworld.slot_service.application.service.SlotStatusQueryService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.response.SlotStatusResponse;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 슬롯 상태 일괄 조회(내부 API용)를 실제 PostgreSQL로 확인한다
// 컨텍스트 설정은 다른 슬롯 DB 테스트와 같게 두어 기존 컨텍스트를 재사용한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotStatusQueryServiceTest extends PostgresContainerSupport {

    @Autowired private SlotStatusQueryService slotStatusQueryService;
    @Autowired private SlotRepository slotRepository;

    private final List<UUID> savedIds = new ArrayList<>();

    @Test
    void rejectsDeletedAndMissingReservationContexts() {
        Slot deleted = SlotFixture.available();
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.of(2026, 11, 1, 12, 0));
        UUID deletedId = save(deleted).getId();

        for (UUID id : List.of(deletedId, UUID.randomUUID())) {
            assertThatThrownBy(() -> slotStatusQueryService.getReservationContext(id))
                    .isInstanceOfSatisfying(BaseException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
        }
    }

    @AfterEach
    void cleanUp() {
        slotRepository.deleteAllById(savedIds);
    }

    private Slot save(Slot slot) {
        Slot saved = slotRepository.saveAndFlush(slot);
        savedIds.add(saved.getId());
        return saved;
    }

    @Test
    @DisplayName("여러 슬롯의 상태·점유 예약·HOLD 만료 시각을 한 번에 요청 순서대로 돌려준다")
    void returnsStatusesInRequestOrder() {
        UUID heldReservation = UUID.randomUUID();
        UUID bookedReservation = UUID.randomUUID();
        LocalDateTime heldUntil = LocalDateTime.of(2026, 11, 2, 9, 5);
        Slot held = save(SlotFixture.held(heldReservation, heldUntil));
        Slot booked = save(SlotFixture.booked(bookedReservation));
        Slot available = save(SlotFixture.available());
        Slot unavailable = save(SlotFixture.unavailable());

        List<SlotStatusResponse> result = slotStatusQueryService.getStatuses(
                List.of(booked.getId(), held.getId(), unavailable.getId(), available.getId()));

        assertThat(result).containsExactly(
                new SlotStatusResponse(booked.getId(), SlotStatus.BOOKED, bookedReservation, null),
                new SlotStatusResponse(held.getId(), SlotStatus.HELD, heldReservation, heldUntil),
                new SlotStatusResponse(unavailable.getId(), SlotStatus.UNAVAILABLE, null, null),
                new SlotStatusResponse(available.getId(), SlotStatus.AVAILABLE, null, null));
    }

    @Test
    @DisplayName("삭제된 슬롯과 없는 슬롯은 응답에서 빠지고, 나머지는 그대로 돌려준다")
    void excludesDeletedAndMissingSlots() {
        Slot alive = save(SlotFixture.booked(UUID.randomUUID()));
        Slot deleted = SlotFixture.booked(UUID.randomUUID());
        ReflectionTestUtils.setField(deleted, "deletedAt", LocalDateTime.of(2026, 11, 1, 12, 0));
        deleted = save(deleted);
        UUID missing = UUID.randomUUID();

        List<SlotStatusResponse> result = slotStatusQueryService.getStatuses(
                List.of(deleted.getId(), missing, alive.getId()));

        assertThat(result).extracting(SlotStatusResponse::slotId).containsExactly(alive.getId());
    }

    @Test
    @DisplayName("같은 슬롯 ID가 여러 번 와도 한 번만 응답하고, 빈 목록이면 빈 배열이다")
    void deduplicatesAndHandlesEmpty() {
        Slot slot = save(SlotFixture.available());

        assertThat(slotStatusQueryService.getStatuses(List.of(slot.getId(), slot.getId())))
                .extracting(SlotStatusResponse::slotId).containsExactly(slot.getId());
        assertThat(slotStatusQueryService.getStatuses(List.of())).isEmpty();
    }
}
