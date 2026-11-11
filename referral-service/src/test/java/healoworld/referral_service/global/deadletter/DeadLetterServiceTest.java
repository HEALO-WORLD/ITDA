package healoworld.referral_service.global.deadletter;

import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DeadLetterServiceTest {
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String ORIGINAL_TOPIC = "slot-results";
    private static final String KEY = "reservation-1";
    private static final String PAYLOAD = "{\"reservationId\":\"r-1\"}";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;

    @InjectMocks
    private DeadLetterService deadLetterService;

    // ── 권한 ──

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"REQUESTER", "EXAMINER"})
    @DisplayName("ADMIN이 아니면 조회·재처리·무시 모두 403이고 저장소·Kafka를 건드리지 않는다")
    void rejectsNonAdmin(Role role) {
        UUID id = UUID.randomUUID();

        assertErrorCode(() -> deadLetterService.getDeadLetters(role, new DeadLetterSearchCondition(null, null, null)),
                ErrorCode.ADMIN_ONLY);
        assertErrorCode(() -> deadLetterService.getDeadLetter(role, id), ErrorCode.ADMIN_ONLY);
        assertErrorCode(() -> deadLetterService.reprocess(ADMIN_ID, role, id), ErrorCode.ADMIN_ONLY);
        assertErrorCode(() -> deadLetterService.ignore(ADMIN_ID, role, id), ErrorCode.ADMIN_ONLY);

        verifyNoInteractions(deadLetterEventRepository, kafkaTemplate);
    }

    // ── 목록·상세 조회 ──

    @Test
    @DisplayName("status를 생략하면 전체를 최신 저장순으로 조회하고, 허용되지 않은 size는 10으로 바꾼다")
    void listsAllWhenStatusOmitted() {
        DeadLetterEvent event = pendingEvent("evt-1", "SlotHeld");
        given(deadLetterEventRepository.findAll(any(Pageable.class))).willReturn(new PageImpl<>(List.of(event)));

        PagedResponse<DeadLetterResponse> response =
                deadLetterService.getDeadLetters(Role.ADMIN, new DeadLetterSearchCondition(null, 2, 999));

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(deadLetterEventRepository).findAll(captor.capture());
        Pageable pageable = captor.getValue();
        assertThat(pageable.getPageNumber()).isEqualTo(2);
        assertThat(pageable.getPageSize()).isEqualTo(10);
        assertThat(pageable.getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(response.contents()).singleElement().satisfies(item -> {
            assertThat(item.deadLetterId()).isEqualTo(event.getId());
            assertThat(item.originalTopic()).isEqualTo(ORIGINAL_TOPIC);
            assertThat(item.status()).isEqualTo(DeadLetterStatus.PENDING);
        });
    }

    @Test
    @DisplayName("status를 주면 그 상태만 조회한다")
    void listsByStatus() {
        given(deadLetterEventRepository.findByDeadLetterStatus(eq(DeadLetterStatus.PENDING), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of()));

        deadLetterService.getDeadLetters(Role.ADMIN, new DeadLetterSearchCondition(DeadLetterStatus.PENDING, 0, 30));

        verify(deadLetterEventRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    @DisplayName("상세 조회는 key·payload 원문까지 돌려준다")
    void getsDetail() {
        DeadLetterEvent event = pendingEvent("evt-1", "SlotHeld");
        given(deadLetterEventRepository.findById(event.getId())).willReturn(Optional.of(event));

        DeadLetterDetailResponse response = deadLetterService.getDeadLetter(Role.ADMIN, event.getId());

        assertThat(response.messageKey()).isEqualTo(KEY);
        assertThat(response.payload()).isEqualTo(PAYLOAD);
        assertThat(response.resolvedBy()).isNull();
    }

    @Test
    @DisplayName("없는 실패 메시지는 404")
    void detailNotFound() {
        UUID id = UUID.randomUUID();
        given(deadLetterEventRepository.findById(id)).willReturn(Optional.empty());

        assertErrorCode(() -> deadLetterService.getDeadLetter(Role.ADMIN, id), ErrorCode.DEAD_LETTER_NOT_FOUND);
    }

    // ── 재처리 ──

    @Test
    @DisplayName("재처리는 원래 토픽으로 key·payload·eventId·eventType만 다시 발행하고 REPROCESSED로 바꾼다")
    void reprocessRepublishesToOriginalTopic() {
        DeadLetterEvent event = givenLocked(pendingEvent("evt-1", "SlotHeld"));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(CompletableFuture.completedFuture(null));

        DeadLetterDetailResponse response = deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId());

        ProducerRecord<String, String> sent = captureSent();
        assertThat(sent.topic()).isEqualTo(ORIGINAL_TOPIC);
        assertThat(sent.key()).isEqualTo(KEY);
        assertThat(sent.value()).isEqualTo(PAYLOAD);
        assertThat(headerNames(sent)).containsExactly("eventId", "eventType");   // DLT 헤더·실패 요약은 붙이지 않는다
        assertThat(headerValue(sent, "eventId")).isEqualTo("evt-1");
        assertThat(headerValue(sent, "eventType")).isEqualTo("SlotHeld");

        assertThat(response.status()).isEqualTo(DeadLetterStatus.REPROCESSED);
        assertThat(response.resolvedBy()).isEqualTo(ADMIN_ID);
        assertThat(response.resolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("원래 메시지에 없던 eventId·eventType 헤더는 붙이지 않는다")
    void reprocessSkipsMissingHeaders() {
        DeadLetterEvent event = givenLocked(pendingEvent(null, null));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(CompletableFuture.completedFuture(null));

        deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId());

        assertThat(headerNames(captureSent())).isEmpty();
    }

    @Test
    @DisplayName("브로커가 발행을 거부하면 503이고 상태는 PENDING 그대로다")
    void reprocessFailsWhenBrokerRejects() {
        DeadLetterEvent event = givenLocked(pendingEvent("evt-1", "SlotHeld"));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertErrorCode(() -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()),
                ErrorCode.DEAD_LETTER_REPUBLISH_FAILED);

        assertThat(event.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(event.getResolvedBy()).isNull();
    }

    @Test
    @DisplayName("브로커 응답을 기다리다 시간이 초과되면 503이고 상태는 PENDING 그대로다")
    @SuppressWarnings("unchecked")
    void reprocessFailsOnTimeout() throws Exception {
        DeadLetterEvent event = givenLocked(pendingEvent("evt-1", "SlotHeld"));
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        given(future.get(anyLong(), any(TimeUnit.class))).willThrow(new TimeoutException());
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);

        assertErrorCode(() -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()),
                ErrorCode.DEAD_LETTER_REPUBLISH_FAILED);

        assertThat(event.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.PENDING);
    }

    @Test
    @DisplayName("대기 중 인터럽트되면 503이고 인터럽트 표시를 다시 세운다")
    @SuppressWarnings("unchecked")
    void reprocessRestoresInterruptFlag() throws Exception {
        DeadLetterEvent event = givenLocked(pendingEvent("evt-1", "SlotHeld"));
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        given(future.get(anyLong(), any(TimeUnit.class))).willThrow(new InterruptedException());
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(future);

        try {
            assertErrorCode(() -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()),
                    ErrorCode.DEAD_LETTER_REPUBLISH_FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();   // 다음 테스트에 인터럽트 표시가 남지 않도록 지운다
        }
        assertThat(event.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.PENDING);
    }

    @Test
    @DisplayName("이미 처리된 실패 메시지는 재처리하지 않고 409")
    void reprocessRejectsResolved() {
        DeadLetterEvent event = pendingEvent("evt-1", "SlotHeld");
        event.markIgnored(UUID.randomUUID());
        givenLocked(event);

        assertErrorCode(() -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()),
                ErrorCode.DEAD_LETTER_ALREADY_RESOLVED);

        verifyNoInteractions(kafkaTemplate);
        assertThat(event.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.IGNORED);
    }

    @Test
    @DisplayName("없는 실패 메시지를 재처리하면 404")
    void reprocessNotFound() {
        UUID id = UUID.randomUUID();
        given(deadLetterEventRepository.findByIdForUpdate(id)).willReturn(Optional.empty());

        assertErrorCode(() -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, id), ErrorCode.DEAD_LETTER_NOT_FOUND);
        verifyNoInteractions(kafkaTemplate);
    }

    // ── 무시 ──

    @Test
    @DisplayName("무시는 발행 없이 IGNORED로 바꾸고 처리한 관리자를 기록한다")
    void ignoreMarksIgnored() {
        DeadLetterEvent event = givenLocked(pendingEvent("evt-1", "SlotHeld"));

        DeadLetterDetailResponse response = deadLetterService.ignore(ADMIN_ID, Role.ADMIN, event.getId());

        assertThat(response.status()).isEqualTo(DeadLetterStatus.IGNORED);
        assertThat(response.resolvedBy()).isEqualTo(ADMIN_ID);
        assertThat(response.resolvedAt()).isNotNull();
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @DisplayName("이미 재처리된 실패 메시지는 무시할 수 없고 409")
    void ignoreRejectsResolved() {
        DeadLetterEvent event = pendingEvent("evt-1", "SlotHeld");
        UUID firstAdmin = UUID.randomUUID();
        event.markReprocessed(firstAdmin);
        givenLocked(event);

        assertErrorCode(() -> deadLetterService.ignore(ADMIN_ID, Role.ADMIN, event.getId()),
                ErrorCode.DEAD_LETTER_ALREADY_RESOLVED);

        assertThat(event.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.REPROCESSED);
        assertThat(event.getResolvedBy()).isEqualTo(firstAdmin);
    }

    @Test
    @DisplayName("없는 실패 메시지를 무시하면 404")
    void ignoreNotFound() {
        UUID id = UUID.randomUUID();
        given(deadLetterEventRepository.findByIdForUpdate(id)).willReturn(Optional.empty());

        assertErrorCode(() -> deadLetterService.ignore(ADMIN_ID, Role.ADMIN, id), ErrorCode.DEAD_LETTER_NOT_FOUND);
    }

    // ── 도우미 ──

    private DeadLetterEvent pendingEvent(String eventId, String eventType) {
        return DeadLetterEvent.create(ORIGINAL_TOPIC, 2, 100L, KEY, eventId, eventType, PAYLOAD,
                "java.lang.IllegalArgumentException", "IllegalArgumentException");
    }

    private DeadLetterEvent givenLocked(DeadLetterEvent event) {
        given(deadLetterEventRepository.findByIdForUpdate(event.getId())).willReturn(Optional.of(event));
        return event;
    }

    @SuppressWarnings("unchecked")
    private ProducerRecord<String, String> captureSent() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        return captor.getValue();
    }

    private List<String> headerNames(ProducerRecord<String, String> record) {
        return StreamSupport.stream(record.headers().spliterator(), false).map(Header::key).toList();
    }

    private String headerValue(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private void assertErrorCode(Runnable call, ErrorCode errorCode) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BaseException.class)
                .extracting(e -> ((BaseException) e).getErrorCode())
                .isEqualTo(errorCode);
    }
}
