package healoworld.slot_service.application;

import healoworld.slot_service.application.dto.BookOutcome;
import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration;
import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration.ConflictedSlot;
import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.EquipmentDowntimeWriter;
import healoworld.slot_service.application.service.SlotBookService;
import healoworld.slot_service.application.service.SlotCreateWriter;
import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 장비 중단 등록의 슬롯 차단·상태 이력과, 차단된 슬롯의 HOLD·확정·해제·만료 후속 동작을 실제 Postgres에서 검증한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeWriterTest extends PostgresContainerSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final LocalDateTime DOWN_START = DAY.atTime(10, 0);
    private static final LocalDateTime DOWN_END = DAY.atTime(12, 0);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID INSTITUTION_ID = UUID.randomUUID();

    @Autowired private EquipmentDowntimeWriter equipmentDowntimeWriter;
    @Autowired private SlotHoldService slotHoldService;
    @Autowired private SlotBookService slotBookService;
    @Autowired private SlotReleaseService slotReleaseService;
    @Autowired private SlotHoldExpiryService slotHoldExpiryService;
    @Autowired private SlotCreateWriter slotCreateWriter;
    @Autowired private SlotRepository slotRepository;
    @Autowired private SlotStatusHistoryRepository slotStatusHistoryRepository;
    @Autowired private EquipmentDowntimeRepository equipmentDowntimeRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private JsonMapper jsonMapper;

    private final UUID equipmentId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    // ---------- 슬롯 차단과 상태 이력 ----------

    @Test
    void AVAILABLE_슬롯은_UNAVAILABLE로_바뀌고_DOWNTIME_STARTED_이력이_남는다() {
        Slot slot = save(slot(LocalTime.of(10, 30), LocalTime.of(11, 0), SlotStatus.AVAILABLE, null, null));

        EquipmentDowntimeRegistration result = register(DOWN_START, DOWN_END);

        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getEquipmentDowntimeId()).isEqualTo(result.downtime().getId());
        assertThat(saved.getUpdatedAt()).isEqualTo(FixedClockConfig.NOW);
        assertThat(result.blockedSlotCount()).isEqualTo(1);
        assertThat(result.conflictedSlots()).isEmpty();

        SlotStatusHistory history = slotStatusHistoryRepository.findAll().getFirst();
        assertThat(slotStatusHistoryRepository.count()).isEqualTo(1);
        assertThat(history.getFromStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(history.getToStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(history.getReason()).isEqualTo(SlotStatusChangeReason.DOWNTIME_STARTED);
        assertThat(history.getChangedBy()).isEqualTo(USER_ID);
        assertThat(history.getChangedAt()).isEqualTo(FixedClockConfig.NOW);
        assertThat(history.getReservationId()).isNull();
    }

    @Test
    void 경계만_맞닿는_슬롯과_다른_장비_슬롯은_바뀌지_않는다() {
        Slot endsAtStart = save(slot(LocalTime.of(9, 0), LocalTime.of(10, 0), SlotStatus.AVAILABLE, null, null));
        Slot startsAtEnd = save(slot(LocalTime.of(12, 0), LocalTime.of(13, 0), SlotStatus.AVAILABLE, null, null));
        Slot otherEquipment = save(Slot.create(UUID.randomUUID(), UUID.randomUUID(), DAY,
                LocalTime.of(10, 30), LocalTime.of(11, 0)));

        EquipmentDowntimeRegistration result = register(DOWN_START, DOWN_END);

        assertThat(result.blockedSlotCount()).isZero();
        assertThat(reload(endsAtStart).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(reload(startsAtEnd).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(reload(otherEquipment).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(slotStatusHistoryRepository.count()).isZero();
    }

    @Test
    void 여러_날짜에_걸친_중단은_구간_안의_슬롯만_차단한다() {
        LocalDate secondDay = DAY.plusDays(1);
        LocalDate day3 = DAY.plusDays(2);
        Slot beforeStart = save(Slot.create(equipmentId, UUID.randomUUID(), DAY, LocalTime.of(21, 0), LocalTime.of(22, 0)));
        Slot day1Night = save(Slot.create(equipmentId, UUID.randomUUID(), DAY, LocalTime.of(22, 30), LocalTime.of(23, 0)));
        Slot day2 = save(Slot.create(equipmentId, UUID.randomUUID(), secondDay, LocalTime.of(9, 0), LocalTime.of(10, 0)));
        Slot day3Morning = save(Slot.create(equipmentId, UUID.randomUUID(), day3, LocalTime.of(7, 30), LocalTime.of(8, 30)));
        Slot afterEnd = save(Slot.create(equipmentId, UUID.randomUUID(), day3, LocalTime.of(9, 0), LocalTime.of(10, 0)));

        // 첫날 22:00 ~ 셋째 날 08:00 (시작 경계와 맞닿는 21~22시 슬롯은 제외)
        EquipmentDowntimeRegistration result = register(DAY.atTime(22, 0), day3.atTime(8, 0));

        assertThat(result.blockedSlotCount()).isEqualTo(3);
        assertThat(reload(beforeStart).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(reload(day1Night).getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(reload(day2).getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(reload(day3Morning).getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(reload(afterEnd).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void 이미_UNAVAILABLE인_슬롯은_다른_중단과_겹쳐도_변경과_이력이_없다() {
        UUID firstDowntimeId = UUID.randomUUID();
        Slot slot = save(slot(LocalTime.of(11, 30), LocalTime.of(12, 30), SlotStatus.UNAVAILABLE, null, firstDowntimeId));

        // 11:30~12:30 슬롯은 10~12시 중단과 겹친다
        EquipmentDowntimeRegistration result = register(DOWN_START, DOWN_END);

        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getEquipmentDowntimeId()).isEqualTo(firstDowntimeId);   // 처음 차단한 중단을 유지
        assertThat(result.blockedSlotCount()).isZero();
        assertThat(result.conflictedSlots()).isEmpty();
        assertThat(slotStatusHistoryRepository.count()).isZero();
    }

    // ---------- Outbox 이벤트 ----------

    @Test
    void 등록하면_EquipmentDowntimeStarted_Outbox_1건이_Referral_계약대로_저장된다() {
        EquipmentDowntimeRegistration result = register(DOWN_START, DOWN_END);

        OutboxEvent outbox = outboxEventRepository.findAll().getFirst();
        assertThat(outboxEventRepository.count()).isEqualTo(1);
        assertThat(outbox.getTopic()).isEqualTo("equipment-events");
        assertThat(outbox.getMessageKey()).isEqualTo(equipmentId.toString());
        assertThat(outbox.getEventType()).isEqualTo("EquipmentDowntimeStarted");
        assertThat(outbox.getAggregateType()).isEqualTo("EQUIPMENT_DOWNTIME");
        assertThat(outbox.getAggregateId()).isEqualTo(result.downtime().getId());
        assertThat(outbox.getPublishedAt()).isNull();
        assertThat(outbox.getRetryCount()).isZero();

        // Referral EquipmentDowntimeStartedEvent의 8개 필드만, 같은 이름과 형식으로 담는다 (downtimeId 등 추가 필드 없음)
        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
                "eventId", "eventType", "occurredAt", "equipmentId", "institutionId", "reason", "startAt", "endAt");
        assertThat(payload.get("eventId").asString()).isEqualTo(outbox.getId().toString());   // Kafka 헤더 eventId와 같은 값
        assertThat(payload.get("eventType").asString()).isEqualTo("EquipmentDowntimeStarted");
        assertThat(payload.get("occurredAt").asString()).isEqualTo("2026-10-30T10:00:00");
        assertThat(payload.get("equipmentId").asString()).isEqualTo(equipmentId.toString());
        assertThat(payload.get("institutionId").asString()).isEqualTo(INSTITUTION_ID.toString());
        assertThat(payload.get("reason").asString()).isEqualTo("가상 사유");
        assertThat(payload.get("startAt").asString()).isEqualTo("2026-11-10T10:00:00");
        assertThat(payload.get("endAt").asString()).isEqualTo("2026-11-10T12:00:00");
    }

    @Test
    void 중복_등록이_409로_거부되면_Outbox도_추가되지_않는다() {
        register(DOWN_START, DOWN_END);

        assertThatThrownBy(() -> register(DOWN_START, DOWN_END)).isInstanceOf(BaseException.class);

        assertThat(outboxEventRepository.count()).isEqualTo(1);
    }

    // ---------- HELD 슬롯 ----------

    @Test
    void HELD_슬롯은_예약을_유지한_채_충돌로_보고되고_이력이_없다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        EquipmentDowntimeRegistration result = register(DOWN_START, DOWN_END);

        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(saved.getEquipmentDowntimeId()).isEqualTo(result.downtime().getId());
        assertThat(result.conflictedSlots()).containsExactly(
                new ConflictedSlot(slot.getId(), SlotStatus.HELD, reservationId));
        assertThat(slotStatusHistoryRepository.count()).isZero();
    }

    @Test
    void 차단된_HELD는_확정되지_않고_HOLD도_유지된다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));
        register(DOWN_START, DOWN_END);

        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
        assertThat(reload(slot).getStatus()).isEqualTo(SlotStatus.HELD);
        // 만료 처리(HoldExpired)로 오인하지 않는다 (Outbox에는 중단 등록 이벤트만 있다)
        assertThat(outboxEventRepository.findAll()).extracting("eventType")
                .containsExactly("EquipmentDowntimeStarted");
    }

    @Test
    void 차단된_HELD를_ReleaseHold하면_AVAILABLE이_아닌_UNAVAILABLE이_된다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));
        register(DOWN_START, DOWN_END);

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED_UNAVAILABLE);
        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(saved.getHeldUntil()).isNull();
        SlotStatusHistory history = slotStatusHistoryRepository.findAll().getFirst();
        assertThat(history.getFromStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(history.getToStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(history.getReason()).isEqualTo(SlotStatusChangeReason.RELEASE_HOLD);
    }

    @Test
    void 차단된_HELD가_만료되면_UNAVAILABLE이_되고_HoldExpired는_그대로_기록된다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(held(reservationId, FixedClockConfig.NOW.minusMinutes(1)));
        register(DOWN_START, DOWN_END);

        boolean expired = slotHoldExpiryService.expire(slot.getId(), reservationId, FixedClockConfig.NOW.minusMinutes(1));

        assertThat(expired).isTrue();
        assertThat(reload(slot).getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(outboxEventRepository.findAll()).extracting("eventType")
                .containsExactlyInAnyOrder("EquipmentDowntimeStarted", "HoldExpired");
        assertThat(slotStatusHistoryRepository.findAll()).singleElement()
                .extracting("toStatus").isEqualTo(SlotStatus.UNAVAILABLE);
    }

    @Test
    void 만료된_차단_HELD에_BookSlot이_오면_만료_처리해_UNAVAILABLE이_된다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(held(reservationId, FixedClockConfig.NOW.minusMinutes(1)));
        register(DOWN_START, DOWN_END);

        assertThat(slotBookService.book(slot.getId(), reservationId, UUID.randomUUID()))
                .isEqualTo(BookOutcome.HOLD_EXPIRED);
        assertThat(reload(slot).getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
    }

    // ---------- BOOKED 슬롯 ----------

    @Test
    void BOOKED_슬롯은_예약을_유지하고_ReleaseSlot하면_UNAVAILABLE이_된다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(slot(LocalTime.of(10, 0), LocalTime.of(10, 30), SlotStatus.BOOKED, reservationId, null));

        EquipmentDowntimeRegistration result = register(DOWN_START, DOWN_END);

        Slot blocked = reload(slot);
        assertThat(blocked.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(blocked.getReservationId()).isEqualTo(reservationId);
        assertThat(result.conflictedSlots()).containsExactly(
                new ConflictedSlot(slot.getId(), SlotStatus.BOOKED, reservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED_UNAVAILABLE);
        Slot released = reload(slot);
        assertThat(released.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(released.getReservationId()).isNull();
        assertThat(slotStatusHistoryRepository.findAll()).singleElement()
                .satisfies(h -> {
                    assertThat(h.getFromStatus()).isEqualTo(SlotStatus.BOOKED);
                    assertThat(h.getToStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
                    assertThat(h.getReason()).isEqualTo(SlotStatusChangeReason.RELEASE_SLOT);
                });
    }

    @Test
    void 차단된_BOOKED를_ADMIN이_강제_해제해도_UNAVAILABLE이_된다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(slot(LocalTime.of(10, 0), LocalTime.of(10, 30), SlotStatus.BOOKED, reservationId, null));
        register(DOWN_START, DOWN_END);

        assertThat(slotReleaseService.forceRelease(slot.getId(), reservationId, USER_ID))
                .isEqualTo(ReleaseOutcome.RELEASED_UNAVAILABLE);
        assertThat(reload(slot).getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
    }

    @Test
    void 차단된_슬롯에는_새_HOLD가_실패한다() {
        Slot slot = save(slot(LocalTime.of(10, 30), LocalTime.of(11, 0), SlotStatus.AVAILABLE, null, null));
        register(DOWN_START, DOWN_END);

        var result = slotHoldService.hold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(HoldFailureReason.SLOT_CONFLICT);
    }

    // ---------- 중복 등록과 슬롯 생성 ----------

    @Test
    void 같은_장비에_겹치는_중단은_409이고_경계만_맞닿으면_등록된다() {
        register(DOWN_START, DOWN_END);

        assertThatThrownBy(() -> register(DOWN_START.plusMinutes(59), DOWN_END.plusHours(1)))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOWNTIME_OVERLAP);

        register(DOWN_END, DOWN_END.plusHours(1));
        register(DOWN_START.minusHours(1), DOWN_START);
        assertThat(equipmentDowntimeRepository.count()).isEqualTo(3);
    }

    @Test
    void 다른_장비의_같은_기간_중단은_등록된다() {
        register(DOWN_START, DOWN_END);
        equipmentDowntimeWriter.register(UUID.randomUUID(), INSTITUTION_ID, DOWN_START, DOWN_END, "가상 사유", USER_ID);

        assertThat(equipmentDowntimeRepository.count()).isEqualTo(2);
    }

    @Test
    void 중단_기간과_겹치는_슬롯_생성은_409이고_경계_밖_슬롯은_생성된다() {
        register(DOWN_START, DOWN_END);

        assertThatThrownBy(() -> slotCreateWriter.createSlot(equipmentId, UUID.randomUUID(), DAY,
                LocalTime.of(11, 30), LocalTime.of(12, 30)))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_IN_DOWNTIME);

        slotCreateWriter.createSlot(equipmentId, UUID.randomUUID(), DAY, LocalTime.of(12, 0), LocalTime.of(12, 30));
        assertThat(slotRepository.count()).isEqualTo(1);
    }

    // ---------- 회귀: 중단이 없을 때 기존 흐름 ----------

    @Test
    void 중단이_없으면_HOLD_확정_취소가_기존처럼_AVAILABLE로_돌아온다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = save(slot(LocalTime.of(10, 30), LocalTime.of(11, 0), SlotStatus.AVAILABLE, null, null));

        assertThat(slotHoldService.hold(slot.getId(), reservationId, UUID.randomUUID()).isSuccess()).isTrue();
        assertThat(slotBookService.book(slot.getId(), reservationId, UUID.randomUUID())).isEqualTo(BookOutcome.BOOKED);
        assertThat(slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID()))
                .isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(reload(slot).getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    private EquipmentDowntimeRegistration register(LocalDateTime startedAt, LocalDateTime endedAt) {
        return equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID, startedAt, endedAt, "가상 사유", USER_ID);
    }

    private Slot held(UUID reservationId, LocalDateTime heldUntil) {
        Slot slot = slot(LocalTime.of(10, 0), LocalTime.of(10, 30), SlotStatus.HELD, reservationId, null);
        ReflectionTestUtils.setField(slot, "heldUntil", heldUntil);
        return slot;
    }

    private Slot slot(LocalTime start, LocalTime end, SlotStatus status, UUID reservationId, UUID downtimeId) {
        Slot slot = Slot.create(equipmentId, UUID.randomUUID(), DAY, start, end);
        ReflectionTestUtils.setField(slot, "status", status);
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);
        ReflectionTestUtils.setField(slot, "equipmentDowntimeId", downtimeId);
        return slot;
    }

    private Slot save(Slot slot) {
        return slotRepository.saveAndFlush(slot);
    }

    private Slot reload(Slot slot) {
        return slotRepository.findById(slot.getId()).orElseThrow();
    }
}
