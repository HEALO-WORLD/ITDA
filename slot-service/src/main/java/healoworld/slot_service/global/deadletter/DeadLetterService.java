package healoworld.slot_service.global.deadletter;

import healoworld.slot_service.domain.enums.Role;
import healoworld.slot_service.global.dto.PagedResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// DLT에 들어가 저장된 실패 메시지를 관리자가 조회·재처리·무시한다
// Gateway가 ADMIN만 통과시키지만, 서비스에서도 역할을 한 번 더 확인한다
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DeadLetterService {
    // OutboxPublisher와 같은 대기 시간
    private static final long REPUBLISH_TIMEOUT_SECONDS = 15;

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeadLetterEventRepository deadLetterEventRepository;

    // 최신 저장순 목록. status를 생략하면 전체 상태를 조회한다
    @Transactional(readOnly = true)
    public PagedResponse<DeadLetterResponse> getDeadLetters(Role role, DeadLetterSearchCondition cond) {
        verifyAdmin(role);

        Pageable pageable = cond.toPageable();
        Page<DeadLetterEvent> page = cond.status() == null
                ? deadLetterEventRepository.findAll(pageable)
                : deadLetterEventRepository.findByDeadLetterStatus(cond.status(), pageable);
        return PagedResponse.from(page.map(DeadLetterResponse::from));
    }

    @Transactional(readOnly = true)
    public DeadLetterDetailResponse getDeadLetter(Role role, UUID deadLetterId) {
        verifyAdmin(role);

        DeadLetterEvent event = deadLetterEventRepository.findById(deadLetterId)
                .orElseThrow(() -> new BaseException(ErrorCode.DEAD_LETTER_NOT_FOUND));
        return DeadLetterDetailResponse.from(event);
    }

    public DeadLetterDetailResponse reprocess(UUID userId, Role role, UUID deadLetterId) {
        verifyAdmin(role);

        DeadLetterEvent event = getPendingForUpdate(deadLetterId);

        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getOriginalTopic(), event.getMessageKey(), event.getPayload());
        addHeader(record, "eventId", event.getEventId());
        addHeader(record, "eventType", event.getEventType());
        try {
            kafkaTemplate.send(record).get(REPUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BaseException(ErrorCode.DEAD_LETTER_REPUBLISH_FAILED);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("실패 메시지 재발행 실패 id={} topic={}", event.getId(), event.getOriginalTopic(), e);
            throw new BaseException(ErrorCode.DEAD_LETTER_REPUBLISH_FAILED);
        }

        event.markReprocessed(userId);
        return DeadLetterDetailResponse.from(event);
    }

    public DeadLetterDetailResponse ignore(UUID userId, Role role, UUID deadLetterId) {
        verifyAdmin(role);

        DeadLetterEvent event = getPendingForUpdate(deadLetterId);
        event.markIgnored(userId);
        return DeadLetterDetailResponse.from(event);
    }

    private void verifyAdmin(Role role) {
        if (role != Role.ADMIN) {
            throw new BaseException(ErrorCode.ADMIN_ONLY);
        }
    }

    private DeadLetterEvent getPendingForUpdate(UUID deadLetterId) {
        DeadLetterEvent event = deadLetterEventRepository.findByIdForUpdate(deadLetterId)
                .orElseThrow(() -> new BaseException(ErrorCode.DEAD_LETTER_NOT_FOUND));
        if (!event.isPending()) {
            throw new BaseException(ErrorCode.DEAD_LETTER_ALREADY_RESOLVED);
        }
        return event;
    }

    private void addHeader(ProducerRecord<String, String> record, String name, String value) {
        if (value != null) {
            record.headers().add(name, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
