package healoworld.referral_service.presentation.messaging;

import healoworld.referral_service.application.service.SlotResultService;
import healoworld.referral_service.global.outbox.ReservationEventPayload;
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
class SlotResultListenerTest {
    private static final String PAYLOAD = "{}";

    @Mock
    private SlotResultService slotResultService;

    @Mock
    private JsonMapper jsonMapper;

    @InjectMocks
    private SlotResultListener slotResultListener;

    @Test
    @DisplayName("헤더의 eventId·eventType과 payload의 예약·의뢰 ID를 서비스에 넘긴다")
    void delegates() {
        UUID eventId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        stubPayload(reservationId, referralId);

        slotResultListener.onSlotResult(eventId.toString(), "SlotHeld", PAYLOAD);

        verify(slotResultService).handle(eventId, "SlotHeld", reservationId, referralId);
    }

    @Test
    @DisplayName("SlotsBlocked인데 예약이 없는 슬롯이면 서비스를 호출하지 않고 끝낸다")
    void slotsBlockedWithoutReservation() {
        stubPayload(null, null);

        slotResultListener.onSlotResult(UUID.randomUUID().toString(), "SlotsBlocked", PAYLOAD);

        verifyNoInteractions(slotResultService);
    }

    @Test
    @DisplayName("SlotsBlocked가 아닌데 reservationId가 없으면 계약 위반으로 재시도 없이 실패한다")
    void missingReservationId() {
        stubPayload(null, null);

        assertThatThrownBy(() -> slotResultListener.onSlotResult(UUID.randomUUID().toString(), "SlotHeld", PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(slotResultService);
    }

    @Test
    @DisplayName("eventId 헤더가 UUID가 아니면 재시도 없이 실패한다")
    void invalidEventId() {
        assertThatThrownBy(() -> slotResultListener.onSlotResult("not-a-uuid", "SlotHeld", PAYLOAD))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(slotResultService);
    }

    private void stubPayload(UUID reservationId, UUID referralId) {
        ReservationEventPayload payload = new ReservationEventPayload(reservationId, referralId, 1, UUID.randomUUID(),
                null, null, null, null, null, null, null, null, LocalDateTime.now());
        given(jsonMapper.readValue(PAYLOAD, ReservationEventPayload.class)).willReturn(payload);
    }
}
