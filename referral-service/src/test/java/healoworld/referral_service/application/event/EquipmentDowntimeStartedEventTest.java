package healoworld.referral_service.application.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EquipmentDowntimeStartedEventTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    @DisplayName("장비 장애 이벤트 JSON을 DTO로 변환한다")
    void deserializeEquipmentDowntimeStartedEvent() {
        // given: Kafka에서 전달받았다고 가정한 JSON
        String json = """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440000",
                  "eventType": "EquipmentDowntimeStarted",
                  "occurredAt": "2026-11-03T09:00:00",
                  "equipmentId": "550e8400-e29b-41d4-a716-446655440001",
                  "institutionId": "550e8400-e29b-41d4-a716-446655440002",
                  "reason": "CT 장비 냉각 장치 고장",
                  "startAt": "2026-11-03T09:00:00",
                  "endAt": "2026-11-03T18:00:00"
                }
                """;

        // when: JSON을 Java 객체로 변환
        EquipmentDowntimeStartedEvent event =
                jsonMapper.readValue(json, EquipmentDowntimeStartedEvent.class);

        // then: 변환 결과 검증
        assertThat(event.eventId())
                .isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));

        assertThat(event.eventType())
                .isEqualTo(EquipmentDowntimeStartedEvent.TYPE);

        assertThat(event.equipmentId())
                .isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"));

        assertThat(event.institutionId())
                .isEqualTo(UUID.fromString("550e8400-e29b-41d4-a716-446655440002"));

        assertThat(event.reason())
                .isEqualTo("CT 장비 냉각 장치 고장");

        assertThat(event.occurredAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 9, 0));

        assertThat(event.startAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 9, 0));

        assertThat(event.endAt())
                .isEqualTo(LocalDateTime.of(2026, 11, 3, 18, 0));
    }

    @Test
    @DisplayName("잘못된 JSON 형식은 변환에 실패한다")
    void invalidJsonThrowsException() {
        // given
        String invalidJson = """
                {
                  "eventId":
                }
                """;

        // when & then
        assertThatThrownBy(() ->
                jsonMapper.readValue(
                        invalidJson,
                        EquipmentDowntimeStartedEvent.class
                )
        ).isInstanceOf(JacksonException.class);
    }
}