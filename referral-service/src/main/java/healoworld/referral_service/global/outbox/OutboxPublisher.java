package healoworld.referral_service.global.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private static final long SEND_TIMEOUT_SECONDS = 15;   // Kafka 응답 대기 시간

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelayString = "${outbox.publish.fixed-delay-ms:1000}")   // 회차 종료 후 outbox.publish.fixed-delay-ms 뒤 시작
    public void publish() {
        List<OutboxEvent> events = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : events) {
            try {
                // 브로커 ack(acks=all)를 받을 때까지 대기
                kafkaTemplate.send(toRecord(event)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                outboxEventRepository.increaseRetryCount(event.getId(), LocalDateTime.now());
                log.warn("Outbox 발행 실패, 회차 중단. id={}, type={}, topic={}, retry={}",
                        event.getId(), event.getEventType(), event.getTopic(),
                        event.getRetryCount() + 1, e);
                return; // 뒤 이벤트가 먼저 나가지 않도록 이번 회차 중단
            }
            outboxEventRepository.markPublished(event.getId(), LocalDateTime.now());
            log.debug("Outbox 발행 완료. id={}, type={}", event.getId(), event.getEventType());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
