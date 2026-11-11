package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.global.outbox.OutboxEvent;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Outbox 릴레이(OutboxPublisher)가 쓰는 조회·조건부 UPDATE를 실제 DB로 검증한다
// 다른 테스트가 남긴 행과 섞이지 않도록 생성 시각을 아주 과거로 두어 조회 결과의 맨 앞에 오게 한다
class OutboxEventRepositoryTest extends RepositoryTestSupport {
    private static final LocalDateTime BASE = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 11, 12, 0);

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("미발행 이벤트만 생성 시각 오름차순으로 조회한다 (발행 완료는 제외)")
    void findsUnpublishedOldestFirst() {
        OutboxEvent third = save(BASE.plusMinutes(3));
        OutboxEvent first = save(BASE.plusMinutes(1));
        OutboxEvent published = save(BASE.plusMinutes(2));
        OutboxEvent second = save(BASE.plusMinutes(2).plusSeconds(30));
        outboxEventRepository.markPublished(published.getId(), NOW);

        List<UUID> ids = mine(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc(),
                List.of(first, second, third, published));

        assertThat(ids).containsExactly(first.getId(), second.getId(), third.getId());
    }

    @Test
    @DisplayName("한 회차에 최대 100건만 조회하고, 가장 오래된 100건이다")
    void limitsTo100Oldest() {
        List<OutboxEvent> saved = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            saved.add(save(BASE.plusSeconds(i)));
        }

        List<OutboxEvent> found = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

        assertThat(found).hasSize(100);
        assertThat(found.stream().map(OutboxEvent::getId).toList())
                .containsExactlyElementsOf(saved.subList(0, 100).stream().map(OutboxEvent::getId).toList());
    }

    @Test
    @DisplayName("markPublished: 발행 시각을 기록하고 1을 돌려주며, 다음 조회부터 빠진다")
    void markPublished() {
        OutboxEvent event = save(BASE);

        int updated = outboxEventRepository.markPublished(event.getId(), NOW);

        assertThat(updated).isEqualTo(1);
        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getPublishedAt()).isEqualTo(NOW);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW);
        assertThat(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .extracting(OutboxEvent::getId).doesNotContain(event.getId());
    }

    @Test
    @DisplayName("increaseRetryCount: 재시도 횟수만 1씩 올리고 미발행으로 남아 다음 회차에 다시 조회된다")
    void increaseRetryCount() {
        OutboxEvent event = save(BASE);

        outboxEventRepository.increaseRetryCount(event.getId(), NOW);
        outboxEventRepository.increaseRetryCount(event.getId(), NOW.plusSeconds(1));

        OutboxEvent reloaded = outboxEventRepository.findById(event.getId()).orElseThrow();
        assertThat(reloaded.getRetryCount()).isEqualTo(2);
        assertThat(reloaded.getPublishedAt()).isNull();
        assertThat(reloaded.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .extracting(OutboxEvent::getId).contains(event.getId());
    }

    @Test
    @DisplayName("없는 이벤트 ID면 두 UPDATE 모두 0을 돌려준다")
    void unknownIdUpdatesNothing() {
        assertThat(outboxEventRepository.markPublished(UUID.randomUUID(), NOW)).isZero();
        assertThat(outboxEventRepository.increaseRetryCount(UUID.randomUUID(), NOW)).isZero();
    }

    private OutboxEvent save(LocalDateTime createdAt) {
        UUID slotId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create(UUID.randomUUID(), "RESERVATION", UUID.randomUUID(), "slot-commands",
                slotId.toString(), "HoldRequested", "{\"slotId\":\"" + slotId + "\"}");
        ReflectionTestUtils.setField(event, "createdAt", createdAt);
        ReflectionTestUtils.setField(event, "updatedAt", createdAt);
        OutboxEvent saved = outboxEventRepository.saveAndFlush(event);
        em.clear();
        return saved;
    }

    // 조회 결과에서 이 테스트가 만든 행만 순서를 유지해 남긴다
    private static List<UUID> mine(List<OutboxEvent> found, List<OutboxEvent> created) {
        List<UUID> createdIds = created.stream().map(OutboxEvent::getId).toList();
        return found.stream().map(OutboxEvent::getId).filter(createdIds::contains).toList();
    }
}
