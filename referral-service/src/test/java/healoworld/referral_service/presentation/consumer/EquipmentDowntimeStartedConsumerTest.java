package healoworld.referral_service.presentation.consumer;

import healoworld.referral_service.application.event.EquipmentDowntimeStartedEvent;
import healoworld.referral_service.application.service.EquipmentIncidentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class EquipmentDowntimeStartedConsumerTest {

    private final EquipmentIncidentService equipmentIncidentService =
            mock(EquipmentIncidentService.class);

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private final EquipmentDowntimeStartedConsumer consumer =
            new EquipmentDowntimeStartedConsumer(
                    equipmentIncidentService,
                    jsonMapper
            );

    // 테스트용 Kafka 메시지 생성
    private ConsumerRecord<String, String> createRecord(
            String key,
            String json,
            String eventType
    ) {
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>(
                        "equipment-events",
                        0,
                        0L,
                        key,
                        json
                );

        if (eventType != null) {
            record.headers().add(
                    "eventType",
                    eventType.getBytes(StandardCharsets.UTF_8)
            );
        }

        return record;
    }

    @Test
    @DisplayName("정상적인 장비 장애 이벤트를 수신하면 장애 등록과 후속 작업 생성을 실행한다")
    void consumeValidEvent() {

        // given: 테스트 이벤트 준비
        UUID eventId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        UUID incidentId = UUID.randomUUID();

        LocalDateTime occurredAt = LocalDateTime.of(2026, 11, 3, 9, 0);
        LocalDateTime startAt = LocalDateTime.of(2026, 11, 3, 9, 0);
        LocalDateTime endAt = LocalDateTime.of(2026, 11, 3, 12, 0);

        EquipmentDowntimeStartedEvent event =
                new EquipmentDowntimeStartedEvent(
                        eventId,
                        EquipmentDowntimeStartedEvent.TYPE,
                        occurredAt,
                        equipmentId,
                        institutionId,
                        "MRI 장비 고장",
                        startAt,
                        endAt
                );

        String json = jsonMapper.writeValueAsString(event);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "equipment-events",
                0,
                0L,
                equipmentId.toString(),
                json
        );

        record.headers().add(
                "eventType",
                EquipmentDowntimeStartedEvent.TYPE.getBytes(StandardCharsets.UTF_8)
        );

        when(equipmentIncidentService.register(
                eventId,
                equipmentId,
                institutionId,
                "MRI 장비 고장",
                occurredAt,
                startAt,
                endAt
        )).thenReturn(incidentId);

        // when: Consumer 실행
        consumer.consume(record);

        // then: 서비스 메서드 호출 순서 및 횟수 검증
        InOrder inOrder = inOrder(equipmentIncidentService);

        inOrder.verify(equipmentIncidentService).register(
                eventId,
                equipmentId,
                institutionId,
                "MRI 장비 고장",
                occurredAt,
                startAt,
                endAt
        );

        inOrder.verify(equipmentIncidentService)
                .createPendingReferrals(incidentId);

        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("equipmentId가 누락되면 예외가 발생하고 장애를 등록하지 않는다")
    void missingEquipmentIdThrowsException() {

        // given: 장비 ID가 없는 이벤트
        EquipmentDowntimeStartedEvent event =
                new EquipmentDowntimeStartedEvent(
                        UUID.randomUUID(),
                        EquipmentDowntimeStartedEvent.TYPE,
                        LocalDateTime.of(2026, 11, 3, 9, 0),
                        null, // equipmentId 누락
                        UUID.randomUUID(),
                        "MRI 장비 고장",
                        LocalDateTime.of(2026, 11, 3, 9, 0),
                        LocalDateTime.of(2026, 11, 3, 12, 0)
                );

        String json = jsonMapper.writeValueAsString(event);

        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "equipment-events",
                0,
                0L,
                UUID.randomUUID().toString(),
                json
        );

        record.headers().add(
                "eventType",
                EquipmentDowntimeStartedEvent.TYPE.getBytes(StandardCharsets.UTF_8)
        );

        // when & then: 잘못된 이벤트이므로 예외 발생
        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("필수 필드");

        // 장애 등록 서비스가 호출되지 않았는지 검증
        verifyNoInteractions(equipmentIncidentService);
    }

    @Test
    @DisplayName("Kafka 메시지 Key와 equipmentId가 다르면 예외가 발생한다")
    void mismatchedEquipmentIdThrowsException() {

        // given
        UUID equipmentId = UUID.randomUUID();
        UUID differentEquipmentId = UUID.randomUUID();

        EquipmentDowntimeStartedEvent event =
                new EquipmentDowntimeStartedEvent(
                        UUID.randomUUID(),
                        EquipmentDowntimeStartedEvent.TYPE,
                        LocalDateTime.of(2026, 11, 3, 9, 0),
                        equipmentId,
                        UUID.randomUUID(),
                        "MRI 장비 고장",
                        LocalDateTime.of(2026, 11, 3, 9, 0),
                        LocalDateTime.of(2026, 11, 3, 12, 0)
                );

        String json = jsonMapper.writeValueAsString(event);

        // 이벤트의 equipmentId와 다른 값을 Kafka Key로 설정
        ConsumerRecord<String, String> record = new ConsumerRecord<>(
                "equipment-events",
                0,
                0L,
                differentEquipmentId.toString(),
                json
        );

        record.headers().add(
                "eventType",
                EquipmentDowntimeStartedEvent.TYPE.getBytes(StandardCharsets.UTF_8)
        );

        // when & then
        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("메시지 키");

        // 잘못된 이벤트이므로 장애 등록을 실행하면 안 됨
        verifyNoInteractions(equipmentIncidentService);
    }

    @Test
    @DisplayName("eventType 헤더가 없으면 이벤트를 처리하지 않는다")
    void missingEventTypeHeader() {

        ConsumerRecord<String, String> record =
                createRecord("equipment-1", "{}", null);

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventType 헤더");

        verifyNoInteractions(equipmentIncidentService);
    }

    @Test
    @DisplayName("지원하지 않는 이벤트 타입이면 처리하지 않는다")
    void unsupportedEventType() {

        ConsumerRecord<String, String> record =
                createRecord("equipment-1", "{}", "UnknownEvent");

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("지원하지 않는 이벤트 유형");

        verifyNoInteractions(equipmentIncidentService);
    }

    @Test
    @DisplayName("잘못된 JSON을 수신하면 파싱 예외가 발생한다")
    void malformedJson() {

        ConsumerRecord<String, String> record =
                createRecord(
                        "equipment-1",
                        "{\"eventId\":}",
                        EquipmentDowntimeStartedEvent.TYPE
                );

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(JacksonException.class);

        verifyNoInteractions(equipmentIncidentService);
    }

    @Test
    @DisplayName("장애 등록 중 오류가 발생하면 예외를 전파하고 후속 처리를 실행하지 않는다")
    void registerFailure() {

        UUID equipmentId = UUID.randomUUID();

        EquipmentDowntimeStartedEvent event =
                new EquipmentDowntimeStartedEvent(
                        UUID.randomUUID(),
                        EquipmentDowntimeStartedEvent.TYPE,
                        LocalDateTime.of(2026, 11, 3, 9, 0),
                        equipmentId,
                        UUID.randomUUID(),
                        "MRI 장비 고장",
                        LocalDateTime.of(2026, 11, 3, 9, 0),
                        LocalDateTime.of(2026, 11, 3, 12, 0)
                );

        ConsumerRecord<String, String> record =
                createRecord(
                        equipmentId.toString(),
                        jsonMapper.writeValueAsString(event),
                        EquipmentDowntimeStartedEvent.TYPE
                );

        when(equipmentIncidentService.register(
                any(), any(), any(), any(), any(), any(), any()
        )).thenThrow(new IllegalStateException("DB 연결 실패"));

        assertThatThrownBy(() -> consumer.consume(record))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB 연결 실패");

        verify(equipmentIncidentService, never())
                .createPendingReferrals(any());
    }

}