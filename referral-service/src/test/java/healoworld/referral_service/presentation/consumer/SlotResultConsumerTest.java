package healoworld.referral_service.presentation.consumer;

import healoworld.referral_service.application.service.SlotResultService;
import healoworld.referral_service.application.event.result.SlotResultMessage;
import healoworld.referral_service.application.event.result.SlotResultType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SlotResultConsumerTest {
    private static final String PAYLOAD = "{}";

    @Mock
    private SlotResultService slotResultService;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private SlotResultConsumer slotResultConsumer;

    @Test
    @DisplayName("헤더의 eventId·eventType과 payload의 예약 ID를 서비스에 넘긴다 (slot-results에는 referralId가 없다)")
    void delegates() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        stubPayload(reservationId);

        slotResultConsumer.consume(eventId.toString(), "SlotHeld", PAYLOAD);

        verify(slotResultService).handle(eventId, SlotResultType.SLOT_HELD, reservationId);
    }

    @Test
    @DisplayName("SlotsBlocked인데 예약이 없는 슬롯이면 서비스를 호출하지 않고 끝낸다")
    void slotsBlockedWithoutReservation() {
        stubPayload(null);

        slotResultConsumer.consume(UUID.randomUUID().toString(), "SlotsBlocked", PAYLOAD);

        verifyNoInteractions(slotResultService);
    }

    @Test
    @DisplayName("SlotsBlocked가 아닌데 reservationId가 없으면 계약 위반으로 재시도 없이 실패한다")
    void missingReservationId() {
        stubPayload(null);

        assertThatThrownBy(() -> slotResultConsumer.consume(UUID.randomUUID().toString(), "SlotHeld", PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(slotResultService);
    }

    @Test
    @DisplayName("모르는 eventType이면 payload를 해석하지 않고 서비스도 호출하지 않는다 (예외 없이 넘어가 DLT로 보내지 않는다)")
    void unknownEventType() {
        slotResultConsumer.consume(UUID.randomUUID().toString(), "SomethingNew", PAYLOAD);

        verifyNoInteractions(slotResultService, jsonMapper);
    }

    @Test
    @DisplayName("eventId 헤더가 UUID가 아니면 재시도 없이 실패한다")
    void invalidEventId() {
        assertThatThrownBy(() -> slotResultConsumer.consume("not-a-uuid", "SlotHeld", PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(slotResultService);
    }

    private void stubPayload(UUID reservationId) {
        SlotResultMessage message = new SlotResultMessage(UUID.randomUUID(), "SlotHeld", LocalDateTime.now(), UUID.randomUUID(), reservationId);
        given(jsonMapper.readValue(PAYLOAD, SlotResultMessage.class)).willReturn(message);
    }
}
