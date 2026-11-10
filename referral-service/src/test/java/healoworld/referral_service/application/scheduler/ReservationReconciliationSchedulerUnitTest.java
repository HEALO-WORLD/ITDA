package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.ReservationReconciliationService;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusQueryRequest;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusResponse;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ReconciliationTarget;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationReconciliationSchedulerUnitTest {
    private static final int BATCH_SIZE = 2;
    private static final UUID MIN_ID = new UUID(0L, 0L);

    @Mock
    private ReservationReconciliationService reconciliationService;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private SlotClient slotClient;

    @Mock
    private ExaminationRepository examinationRepository;

    private ReservationReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ReservationReconciliationScheduler(
                reconciliationService, reservationRepository, slotClient, examinationRepository, BATCH_SIZE, 24, 10);

        // 기본값: 세 쿼리 모두 대상 없음. 테스트마다 필요한 쿼리만 덮어쓴다
        lenient().when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of());
        lenient().when(reservationRepository.findUpcomingConfirmed(any(), any(), any())).thenReturn(List.of());
        lenient().when(reservationRepository.findRecentlyEnded(any(), any(), any(), any())).thenReturn(List.of());
        // 기본값: 검사가 시작·완료된 예약 없음
        lenient().when(examinationRepository.findReservationIdsWithStartedExamination(any())).thenReturn(List.of());
    }

    private static ReconciliationTarget target(ReservationStatus status) {
        return new ReconciliationTarget(UUID.randomUUID(), UUID.randomUUID(), status);
    }

    private static ReconciliationTarget target(ReservationStatus status, UUID slotId) {
        return new ReconciliationTarget(UUID.randomUUID(), slotId, status);
    }

    private static SlotStatusResponse slot(ReconciliationTarget target, String status, UUID reservationId) {
        LocalDateTime heldUntil = "HELD".equals(status) ? LocalDateTime.now().plusMinutes(3) : null;
        return new SlotStatusResponse(target.slotId(), status, reservationId, heldUntil);
    }

    @Test
    @DisplayName("대상이 없으면 slot-service를 호출하지 않는다")
    void noTargets() {
        scheduler.reconcileReservations();

        verifyNoInteractions(slotClient, reconciliationService);
    }

    @Test
    @DisplayName("불일치가 있는 예약만 판단 결과 이벤트로 복구한다")
    void reconcilesOnlyMismatched() {
        ReconciliationTarget lostHeld = target(ReservationStatus.REQUESTED);    // 슬롯은 이 예약이 HELD → SLOT_HELD
        ReconciliationTarget normal = target(ReservationStatus.CONFIRMED);      // 이 예약이 BOOKED → 정상
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of(lostHeld));
        when(reservationRepository.findUpcomingConfirmed(any(), any(), any())).thenReturn(List.of(normal));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(
                slot(lostHeld, "HELD", lostHeld.reservationId()),
                slot(normal, "BOOKED", normal.reservationId())));

        scheduler.reconcileReservations();

        verify(reconciliationService).reconcile(eq(lostHeld), eq(SlotResultType.SLOT_HELD), any());
        verify(reconciliationService, never()).reconcile(eq(normal), any(), any());
    }

    @Test
    @DisplayName("같은 슬롯에 걸린 예약이 여러 건이면 slotId는 한 번만 요청하고, 예약은 각각 판단한다")
    void distinctSlotIds() {
        UUID sharedSlotId = UUID.randomUUID();
        ReconciliationTarget canceled = target(ReservationStatus.CANCELED, sharedSlotId);
        ReconciliationTarget requested = target(ReservationStatus.REQUESTED, sharedSlotId);
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of(requested));
        when(reservationRepository.findRecentlyEnded(any(), any(), any(), any())).thenReturn(List.of(canceled));
        // 슬롯은 새 예약(requested)이 HELD 중
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(slot(requested, "HELD", requested.reservationId())));

        scheduler.reconcileReservations();

        ArgumentCaptor<SlotStatusQueryRequest> captor = ArgumentCaptor.forClass(SlotStatusQueryRequest.class);
        verify(slotClient).getSlotStatuses(captor.capture());
        assertThat(captor.getValue().slotIds()).containsExactly(sharedSlotId);

        verify(reconciliationService).reconcile(eq(requested), eq(SlotResultType.SLOT_HELD), any());
        verify(reconciliationService, never()).reconcile(eq(canceled), any(), any());   // 다른 예약이 잡은 슬롯이라 손대지 않음
    }

    @Test
    @DisplayName("slot-service 응답에 없는 슬롯은 건너뛰고 다음 예약을 처리한다")
    void skipsMissingSlot() {
        ReconciliationTarget missing = target(ReservationStatus.REQUESTED);
        ReconciliationTarget lostHeld = target(ReservationStatus.REQUESTED);
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of(missing, lostHeld));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(slot(lostHeld, "HELD", lostHeld.reservationId())));

        scheduler.reconcileReservations();

        verify(reconciliationService, never()).reconcile(eq(missing), any(), any());
        verify(reconciliationService).reconcile(eq(lostHeld), eq(SlotResultType.SLOT_HELD), any());
    }

    @Test
    @DisplayName("한 건의 복구가 실패해도 나머지 예약은 계속 처리한다")
    void continuesAfterFailure() {
        ReconciliationTarget first = target(ReservationStatus.REQUESTED);
        ReconciliationTarget second = target(ReservationStatus.REQUESTED);
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of(first, second));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(
                slot(first, "HELD", first.reservationId()),
                slot(second, "HELD", second.reservationId())));
        doThrow(new IllegalStateException("boom"))
                .when(reconciliationService).reconcile(eq(first), any(), any());

        scheduler.reconcileReservations();

        verify(reconciliationService).reconcile(eq(second), eq(SlotResultType.SLOT_HELD), any());
    }

    @Test
    @DisplayName("slot-service 응답에 같은 슬롯이 중복돼도 예외 없이 처리한다")
    void toleratesDuplicateSlotResponse() {
        ReconciliationTarget lostHeld = target(ReservationStatus.REQUESTED);
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of(lostHeld));
        SlotStatusResponse response = slot(lostHeld, "HELD", lostHeld.reservationId());
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(response, response));

        scheduler.reconcileReservations();

        verify(reconciliationService).reconcile(eq(lostHeld), eq(SlotResultType.SLOT_HELD), any());
    }

    @Test
    @DisplayName("조회 결과가 batchSize만큼 차면 다음 회차는 마지막 id 이후부터 조회한다")
    void advancesCursorWhenFull() {
        List<ReconciliationTarget> full = IntStream.range(0, BATCH_SIZE)
                .mapToObj(i -> target(ReservationStatus.REQUESTED)).toList();
        when(reservationRepository.findStaleInProgress(any(), any(), any()))
                .thenReturn(full)
                .thenReturn(List.of());
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of());

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();

        ArgumentCaptor<UUID> lastIds = ArgumentCaptor.forClass(UUID.class);
        verify(reservationRepository, times(2)).findStaleInProgress(any(), lastIds.capture(), any());
        assertThat(lastIds.getAllValues()).containsExactly(MIN_ID, full.getLast().reservationId());
    }

    @Test
    @DisplayName("조회 결과가 batchSize보다 적으면 다음 회차는 처음부터 조회한다")
    void resetsCursorWhenNotFull() {
        List<ReconciliationTarget> full = IntStream.range(0, BATCH_SIZE)
                .mapToObj(i -> target(ReservationStatus.REQUESTED)).toList();
        when(reservationRepository.findStaleInProgress(any(), any(), any()))
                .thenReturn(full)
                .thenReturn(List.of(target(ReservationStatus.REQUESTED)))
                .thenReturn(List.of());
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of());

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();
        scheduler.reconcileReservations();

        ArgumentCaptor<UUID> lastIds = ArgumentCaptor.forClass(UUID.class);
        verify(reservationRepository, times(3)).findStaleInProgress(any(), lastIds.capture(), any());
        assertThat(lastIds.getAllValues()).containsExactly(MIN_ID, full.getLast().reservationId(), MIN_ID);
    }

    @Test
    @DisplayName("대상이 0건인 회차에도 cursor를 초기화한다 (마지막 페이지가 딱 batchSize였던 경우)")
    void resetsCursorWhenEmpty() {
        List<ReconciliationTarget> full = IntStream.range(0, BATCH_SIZE)
                .mapToObj(i -> target(ReservationStatus.REQUESTED)).toList();
        when(reservationRepository.findStaleInProgress(any(), any(), any()))
                .thenReturn(full)
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of());

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();   // 0건 → 초기화돼야 함
        scheduler.reconcileReservations();

        ArgumentCaptor<UUID> lastIds = ArgumentCaptor.forClass(UUID.class);
        verify(reservationRepository, times(3)).findStaleInProgress(any(), lastIds.capture(), any());
        assertThat(lastIds.getAllValues()).containsExactly(MIN_ID, full.getLast().reservationId(), MIN_ID);
    }

    @Test
    @DisplayName("slot-service 조회가 실패하면 복구하지 않고 cursor도 그대로 둬서 다음 회차에 같은 범위를 다시 본다")
    void keepsCursorWhenSlotServiceFails() {
        List<ReconciliationTarget> full = IntStream.range(0, BATCH_SIZE)
                .mapToObj(i -> target(ReservationStatus.REQUESTED)).toList();
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(full);
        when(slotClient.getSlotStatuses(any())).thenThrow(new RuntimeException("slot-service down"));

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();

        verifyNoInteractions(reconciliationService);
        ArgumentCaptor<UUID> lastIds = ArgumentCaptor.forClass(UUID.class);
        verify(reservationRepository, times(2)).findStaleInProgress(any(), lastIds.capture(), any());
        assertThat(lastIds.getAllValues()).containsExactly(MIN_ID, MIN_ID);
    }

    // ---------- SlotsBlocked 사전 확인 (검사가 이미 시작·완료된 확정 예약은 주입하지 않음) ----------

    @Test
    @DisplayName("A·B. CONFIRMED + SlotsBlocked 후보의 검사가 시작·완료됐으면 이벤트를 주입하지 않는다")
    void skipsConfirmedWithStartedExamination() {
        ReconciliationTarget started = target(ReservationStatus.CONFIRMED);
        when(reservationRepository.findUpcomingConfirmed(any(), any(), any())).thenReturn(List.of(started));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(slot(started, "UNAVAILABLE", null)));
        when(examinationRepository.findReservationIdsWithStartedExamination(List.of(started.reservationId())))
                .thenReturn(List.of(started.reservationId()));

        scheduler.reconcileReservations();

        verifyNoInteractions(reconciliationService);
    }

    @Test
    @DisplayName("C. 검사가 SCHEDULED이거나 없으면(사전 확인 결과 없음) 기존처럼 SlotsBlocked로 복구한다")
    void reconcilesConfirmedWithoutStartedExamination() {
        ReconciliationTarget confirmed = target(ReservationStatus.CONFIRMED);
        when(reservationRepository.findUpcomingConfirmed(any(), any(), any())).thenReturn(List.of(confirmed));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(slot(confirmed, "UNAVAILABLE", null)));

        scheduler.reconcileReservations();

        verify(examinationRepository).findReservationIdsWithStartedExamination(List.of(confirmed.reservationId()));
        verify(reconciliationService).reconcile(eq(confirmed), eq(SlotResultType.SLOTS_BLOCKED), any());
    }

    @Test
    @DisplayName("D. REQUESTED·HELD의 SlotsBlocked는 사전 확인 대상이 아니라 기존처럼 복구한다")
    void requestedAndHeldAreNotFiltered() {
        ReconciliationTarget requested = target(ReservationStatus.REQUESTED);
        ReconciliationTarget held = target(ReservationStatus.HELD);
        when(reservationRepository.findStaleInProgress(any(), any(), any())).thenReturn(List.of(requested, held));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(
                slot(requested, "UNAVAILABLE", null), slot(held, "UNAVAILABLE", null)));

        scheduler.reconcileReservations();

        verify(examinationRepository, never()).findReservationIdsWithStartedExamination(any());
        verify(reconciliationService).reconcile(eq(requested), eq(SlotResultType.SLOTS_BLOCKED), any());
        verify(reconciliationService).reconcile(eq(held), eq(SlotResultType.SLOTS_BLOCKED), any());
    }

    @Test
    @DisplayName("E. 한 회차에 SlotsBlocked 후보가 여러 건이어도 검사 상태는 한 번에 조회하고, 시작된 예약만 뺀다")
    void queriesStartedExaminationOncePerRound() {
        ReconciliationTarget started = target(ReservationStatus.CONFIRMED);
        ReconciliationTarget scheduled = target(ReservationStatus.CONFIRMED);
        ReconciliationTarget normal = target(ReservationStatus.CONFIRMED);   // 슬롯이 이 예약으로 BOOKED → 후보 아님
        when(reservationRepository.findUpcomingConfirmed(any(), any(), any())).thenReturn(List.of(started, scheduled, normal));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(
                slot(started, "UNAVAILABLE", null),
                slot(scheduled, "UNAVAILABLE", null),
                slot(normal, "BOOKED", normal.reservationId())));
        when(examinationRepository.findReservationIdsWithStartedExamination(any())).thenReturn(List.of(started.reservationId()));

        scheduler.reconcileReservations();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> candidates = ArgumentCaptor.forClass(Collection.class);
        verify(examinationRepository, times(1)).findReservationIdsWithStartedExamination(candidates.capture());
        assertThat(candidates.getValue()).containsExactlyInAnyOrder(started.reservationId(), scheduled.reservationId());
        verify(reconciliationService).reconcile(eq(scheduled), eq(SlotResultType.SLOTS_BLOCKED), any());
        verify(reconciliationService, never()).reconcile(eq(started), any(), any());
        verify(reconciliationService, never()).reconcile(eq(normal), any(), any());
    }

    @Test
    @DisplayName("검사 상태 사전 확인이 실패하면 거르지 않고 기존처럼 주입한다 (최종 확인은 SlotsBlocked 처리에서 잠금 아래 수행)")
    void injectsWhenPreCheckFails() {
        ReconciliationTarget confirmed = target(ReservationStatus.CONFIRMED);
        when(reservationRepository.findUpcomingConfirmed(any(), any(), any())).thenReturn(List.of(confirmed));
        when(slotClient.getSlotStatuses(any())).thenReturn(List.of(slot(confirmed, "UNAVAILABLE", null)));
        when(examinationRepository.findReservationIdsWithStartedExamination(any()))
                .thenThrow(new RuntimeException("DB 조회 실패"));

        scheduler.reconcileReservations();

        verify(reconciliationService).reconcile(eq(confirmed), eq(SlotResultType.SLOTS_BLOCKED), any());
    }
}
