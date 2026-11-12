package healoworld.referral_service.global.deadletter;

import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * DeadLetterService·DeadLetterEventRepository 통합 테스트 (테스트 전용 PostgreSQL)
 * <p>
 * - 행 잠금(SELECT ... FOR UPDATE)과 롤백을 실제 DB로 확인하기 위해 테스트 트랜잭션을 끄고, 만든 행은 끝날 때 ID로 지운다.
 * - Kafka 발행은 KafkaTemplate을 Mock으로 바꿔 확인한다 (실제 브로커 발행은 이 테스트의 범위가 아니다).
 */
@DataJpaTest
@Import({DeadLetterService.class, QuerydslConfig.class})   // 같은 슬라이스에 뜨는 Querydsl 커스텀 리포지토리용
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// KafkaTemplate Mock 때문에 다른 테스트와 컨텍스트를 공유하지 못하므로, 끝나면 닫아 DB 연결을 돌려준다 (테스트 DB 연결 수 한도)
@DirtiesContext
class DeadLetterServiceIntegrationTest extends PostgresContainerSupport {
    private static final UUID ADMIN_ID = UUID.randomUUID();
    // 테스트마다 다른 토픽 이름을 써서 다른 테스트가 남긴 행과 섞이지 않게 한다
    private final String topic = "it-dead-letter-" + UUID.randomUUID();

    @Autowired
    private DeadLetterService deadLetterService;

    @Autowired
    private DeadLetterEventRepository deadLetterEventRepository;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    private final List<UUID> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        deadLetterEventRepository.deleteAllById(createdIds);
    }

    @Test
    @DisplayName("같은 원래 위치(topic·partition·offset)는 두 번 저장되지 않는다 (unique 제약)")
    void uniqueOriginalLocation() {
        save(event(0, 10L));

        assertThatThrownBy(() -> deadLetterEventRepository.saveAndFlush(event(0, 10L)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(deadLetterEventRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(topic, 0, 10L)).isTrue();
        assertThat(deadLetterEventRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(topic, 0, 11L)).isFalse();
    }

    @Test
    @DisplayName("상태로 걸러 최신 저장순으로 조회한다")
    void findsByStatusNewestFirst() throws InterruptedException {
        DeadLetterEvent older = save(event(0, 1L));
        TimeUnit.MILLISECONDS.sleep(5);   // createdAt 순서를 확실히 다르게 한다
        DeadLetterEvent newer = save(event(0, 2L));
        DeadLetterEvent ignored = event(0, 3L);
        ignored.markIgnored(ADMIN_ID);
        save(ignored);

        List<UUID> pendingIds = deadLetterEventRepository.findByDeadLetterStatus(DeadLetterStatus.PENDING,
                        PageRequest.of(0, 50, Sort.by(Sort.Direction.DESC, "createdAt")))
                .getContent().stream()
                .filter(e -> e.getOriginalTopic().equals(topic))
                .map(DeadLetterEvent::getId)
                .toList();

        assertThat(pendingIds).containsExactly(newer.getId(), older.getId());
    }

    @Test
    @DisplayName("발행에 실패하면 트랜잭션이 롤백되어 DB 상태는 PENDING 그대로다")
    void republishFailureRollsBack() {
        DeadLetterEvent event = save(event(0, 1L));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThatThrownBy(() -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()))
                .isInstanceOf(BaseException.class);

        DeadLetterEvent reloaded = deadLetterEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(reloaded.getResolvedBy()).isNull();
    }

    @Test
    @DisplayName("두 관리자가 동시에 재처리해도 행 잠금으로 한 번만 발행되고, 늦은 쪽은 409를 받는다")
    void concurrentReprocessPublishesOnce() throws Exception {
        DeadLetterEvent event = save(event(0, 1L));
        // Mock stubbing은 스레드에 안전하지 않으므로 스레드 시작 전에 끝낸다
        // 발행에 시간이 걸리게 해서, 먼저 잠근 쪽이 발행하는 동안 다른 쪽이 잠금을 기다리게 한다
        given(kafkaTemplate.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(300);
            return CompletableFuture.<SendResult<String, String>>completedFuture(null);
        });

        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.await();
                    try {
                        return deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()).status();
                    } catch (BaseException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            ready.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder(DeadLetterStatus.REPROCESSED, ErrorCode.DEAD_LETTER_ALREADY_RESOLVED);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        assertThat(deadLetterEventRepository.findById(event.getId()).orElseThrow().getDeadLetterStatus())
                .isEqualTo(DeadLetterStatus.REPROCESSED);
    }

    @Test
    @DisplayName("재처리 중인 메시지를 동시에 무시하면 잠금을 기다린 뒤 409를 받는다")
    void ignoreWaitsForReprocess() throws Exception {
        DeadLetterEvent event = save(event(0, 1L));
        CountDownLatch publishing = new CountDownLatch(1);
        given(kafkaTemplate.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            publishing.countDown();            // 재처리 쪽이 행을 잠근 채 발행에 들어왔다
            TimeUnit.MILLISECONDS.sleep(300);
            return CompletableFuture.<SendResult<String, String>>completedFuture(null);
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<DeadLetterStatus> reprocess = executor.submit(
                    () -> deadLetterService.reprocess(ADMIN_ID, Role.ADMIN, event.getId()).status());
            assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> deadLetterService.ignore(ADMIN_ID, Role.ADMIN, event.getId()))
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DEAD_LETTER_ALREADY_RESOLVED);
            assertThat(reprocess.get(10, TimeUnit.SECONDS)).isEqualTo(DeadLetterStatus.REPROCESSED);
        } finally {
            executor.shutdownNow();
        }
    }

    private DeadLetterEvent event(int partition, long offset) {
        return DeadLetterEvent.create(topic, partition, offset, "key", UUID.randomUUID().toString(), "SlotHeld",
                "{}", "java.lang.IllegalArgumentException", "IllegalArgumentException");
    }

    private DeadLetterEvent save(DeadLetterEvent event) {
        DeadLetterEvent saved = deadLetterEventRepository.saveAndFlush(event);
        createdIds.add(saved.getId());
        return saved;
    }
}
