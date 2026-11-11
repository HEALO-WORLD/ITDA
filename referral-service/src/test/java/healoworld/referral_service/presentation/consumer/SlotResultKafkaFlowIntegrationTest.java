package healoworld.referral_service.presentation.consumer;

import healoworld.referral_service.application.service.SlotResultService;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * slot-results 수신 → 처리 → 실패 시 DLT 이동 → DLT 저장까지 실제 Kafka·Postgres로 확인한다.
 * <p>
 * - 앱 전체 컨텍스트(실제 KafkaConfig 에러 핸들러·리스너·DeadLetterConsumer)를 띄우고, Kafka는 docker-compose와 같은 이미지의 컨테이너를 쓴다
 * - 재시도 횟수·백오프 같은 정책 세부는 KafkaErrorHandlerPolicyTest, 서비스 처리 규칙은 SlotResultServiceTest가 맡는다.
 *   여기서는 "실제로 연결했을 때" 헤더 파싱·inbox 중복 방지·DLT 이동·DLT 저장·뒤 메시지 처리가 이어지는지만 본다
 * - 토픽은 NewTopic으로 미리 만든다 (운영에서는 docker-compose의 kafka-init이 만든다)
 * - 이 클래스만 쓰는 컨텍스트라 끝나면 닫아 테스트 Postgres 커넥션을 돌려준다 (too many clients 방지)
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.group-id=referral-service-kafka-flow-test",
        "outbox.publish.fixed-delay-ms=200"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SlotResultKafkaFlowIntegrationTest extends PostgresContainerSupport {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0");

    static {
        KAFKA.start();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic slotResults() {
            return TopicBuilder.name("slot-results").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic slotResultsDlt() {
            return TopicBuilder.name("slot-results-dlt").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic slotCommands() {
            return TopicBuilder.name("slot-commands").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic equipmentEvents() {
            return TopicBuilder.name("equipment-events").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic equipmentEventsDlt() {
            return TopicBuilder.name("equipment-events-dlt").partitions(1).replicas(1).build();
        }
    }

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<UUID> referralIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID eventId : eventIds) {
            jdbcTemplate.update("delete from p_consumed_events where id = ?", eventId);
            jdbcTemplate.update("delete from p_dead_letter_events where event_id = ?", eventId.toString());
        }
        for (UUID referralId : referralIds) {
            jdbcTemplate.update("delete from p_referral_histories where referral_id = ?", referralId);
            jdbcTemplate.update("delete from p_outbox_events where aggregate_id in "
                    + "(select id from p_reservations where referral_id = ?)", referralId);
            jdbcTemplate.update("delete from p_reservations where referral_id = ?", referralId);
            jdbcTemplate.update("delete from p_referrals where id = ?", referralId);
        }
    }

    @Test
    @DisplayName("SlotHeld를 받으면 헤더의 eventId·eventType으로 처리해 예약 HELD·BookSlot Outbox·inbox 기록을 남긴다")
    void processesSlotHeld() throws Exception {
        Reserved reserved = requestedReservation();

        UUID eventId = sendSlotResult("SlotHeld", reserved.slotId(), reserved.reservationId());

        awaitUntil("SlotHeld 처리", () -> isConsumed(eventId));
        assertThat(reservationStatus(reserved.reservationId())).isEqualTo(ReservationStatus.HELD.name());
        assertThat(bookSlotCount(reserved.reservationId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select event_type from p_consumed_events where id = ? and consumer = ?", String.class,
                eventId, SlotResultService.CONSUMER)).isEqualTo("SlotHeld");
    }

    @Test
    @DisplayName("같은 eventId의 SlotHeld가 두 번 와도 한 번만 처리한다 (inbox 1건, BookSlot 1건)")
    void duplicateEventProcessedOnce() throws Exception {
        Reserved reserved = requestedReservation();
        UUID eventId = UUID.randomUUID();

        sendSlotResult(eventId, "SlotHeld", reserved.slotId(), reserved.reservationId());
        sendSlotResult(eventId, "SlotHeld", reserved.slotId(), reserved.reservationId());
        // 같은 파티션의 뒤 메시지가 처리되면 앞의 두 메시지도 처리가 끝난 것이다
        Reserved marker = requestedReservation();
        UUID markerEvent = sendSlotResult("SlotHeld", marker.slotId(), marker.reservationId());
        awaitUntil("뒤 메시지 처리", () -> isConsumed(markerEvent));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_consumed_events where id = ?", Integer.class, eventId)).isEqualTo(1);
        assertThat(bookSlotCount(reserved.reservationId())).isEqualTo(1);
        assertThat(reservationStatus(reserved.reservationId())).isEqualTo(ReservationStatus.HELD.name());
        assertThat(deadLetterCount(eventId)).isZero();
    }

    @Test
    @DisplayName("없는 예약의 SlotHeld는 slot-results-dlt로 옮겨져 PENDING 실패 메시지로 저장되고, 뒤 메시지는 막히지 않고 처리된다")
    void unknownReservationGoesToDltAndIsStored() throws Exception {
        UUID unknownReservation = UUID.randomUUID();
        UUID slotId = UUID.randomUUID();

        UUID poisonEvent = sendSlotResult("SlotHeld", slotId, unknownReservation);
        Reserved next = requestedReservation();
        UUID nextEvent = sendSlotResult("SlotHeld", next.slotId(), next.reservationId());

        awaitUntil("DLT 저장", () -> deadLetterCount(poisonEvent) == 1);
        Map<String, Object> deadLetter = jdbcTemplate.queryForMap(
                "select original_topic, message_key, event_type, payload, exception_class, failure_summary, dead_letter_status "
                        + "from p_dead_letter_events where event_id = ?", poisonEvent.toString());
        assertThat(deadLetter.get("original_topic")).isEqualTo("slot-results");
        assertThat(deadLetter.get("message_key")).isEqualTo(slotId.toString());
        assertThat(deadLetter.get("event_type")).isEqualTo("SlotHeld");
        assertThat((String) deadLetter.get("payload")).contains(unknownReservation.toString());
        assertThat((String) deadLetter.get("exception_class")).endsWith("BaseException");
        assertThat((String) deadLetter.get("failure_summary")).contains("RESERVATION_NOT_FOUND");
        assertThat(deadLetter.get("dead_letter_status")).isEqualTo("PENDING");
        assertThat(isConsumed(poisonEvent)).isFalse();

        awaitUntil("뒤 메시지 처리", () -> isConsumed(nextEvent));
        assertThat(reservationStatus(next.reservationId())).isEqualTo(ReservationStatus.HELD.name());
    }

    @Test
    @DisplayName("reservationId가 없는 SlotHeld는 계약 위반(IllegalArgumentException)으로 DLT에 저장된다")
    void missingReservationIdGoesToDlt() throws Exception {
        UUID slotId = UUID.randomUUID();

        UUID eventId = sendSlotResult("SlotHeld", slotId, null);

        awaitUntil("DLT 저장", () -> deadLetterCount(eventId) == 1);
        assertThat(jdbcTemplate.queryForObject(
                "select exception_class from p_dead_letter_events where event_id = ?", String.class, eventId.toString()))
                .isEqualTo(IllegalArgumentException.class.getName());
    }

    // ---------- 도우미 ----------

    private record Reserved(UUID reservationId, UUID slotId) {
    }

    // REQUESTED 상태의 첫 예약 (HoldRequested를 보낸 직후 상태)
    private Reserved requestedReservation() {
        LocalDateTime startsAt = LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID slotId = UUID.randomUUID();
        return new TransactionTemplate(transactionManager).execute(status -> {
            Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "가상환자", UUID.randomUUID()));
            Reservation reservation = Reservation.create(referral, 1, slotId, UUID.randomUUID(), UUID.randomUUID(),
                    startsAt, startsAt, startsAt.plusHours(1));
            reservationRepository.save(reservation);
            referral.assignReservation(reservation.getId());
            referralIds.add(referral.getId());
            return new Reserved(reservation.getId(), slotId);
        });
    }

    private UUID sendSlotResult(String eventType, UUID slotId, UUID reservationId) throws Exception {
        UUID eventId = UUID.randomUUID();
        sendSlotResult(eventId, eventType, slotId, reservationId);
        return eventId;
    }

    // Slot Service OutboxPublisher와 같은 형식: key = slotId, 헤더 eventId·eventType, 한국 시간 LocalDateTime 문자열
    private void sendSlotResult(UUID eventId, String eventType, UUID slotId, UUID reservationId) throws Exception {
        if (!eventIds.contains(eventId)) {
            eventIds.add(eventId);
        }
        String payload = """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-11-11T10:00:00","slotId":"%s","reservationId":%s}
                """.formatted(eventId, eventType, slotId, reservationId == null ? "null" : "\"" + reservationId + "\"");
        ProducerRecord<String, String> record = new ProducerRecord<>("slot-results", slotId.toString(), payload.strip());
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    private boolean isConsumed(UUID eventId) {
        return jdbcTemplate.queryForObject("select count(*) from p_consumed_events where id = ? and consumer = ?",
                Integer.class, eventId, SlotResultService.CONSUMER) > 0;
    }

    private int deadLetterCount(UUID eventId) {
        return jdbcTemplate.queryForObject("select count(*) from p_dead_letter_events where event_id = ?",
                Integer.class, eventId.toString());
    }

    private String reservationStatus(UUID reservationId) {
        return jdbcTemplate.queryForObject("select reservation_status from p_reservations where id = ?",
                String.class, reservationId);
    }

    private int bookSlotCount(UUID reservationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from p_outbox_events where aggregate_id = ? and event_type = 'BookSlot'",
                Integer.class, reservationId);
    }

    // 조건이 참이 될 때까지 기다린다 (컨슈머 그룹 참여·재시도 시간 포함)
    private static void awaitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError(what + "이(가) " + WAIT.toSeconds() + "초 안에 끝나지 않았습니다.");
    }
}
