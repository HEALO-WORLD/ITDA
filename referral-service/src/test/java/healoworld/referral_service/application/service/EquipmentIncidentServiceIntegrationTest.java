package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EquipmentIncidentService 통합 테스트 (테스트 전용 PostgreSQL)
 *
 * - register()는 호출한 쪽 트랜잭션에 참여하지 않고(NOT_SUPPORTED) 저장을 실제로 커밋한다.
 *   그래서 테스트 트랜잭션도 끄고, 테스트가 만든 장애 행만 끝날 때 ID로 지운다(TestDatabaseGuard가 테스트 DB임을 보장).
 */
@DataJpaTest
@Import({EquipmentIncidentService.class, QuerydslConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EquipmentIncidentServiceIntegrationTest {

    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 2, 9, 0);

    @Autowired
    private EquipmentIncidentService equipmentIncidentService;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    private final List<UUID> createdIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        equipmentIncidentRepository.deleteAllById(new HashSet<>(createdIds));
    }

    private UUID register(UUID sourceEventId, UUID equipmentId, UUID institutionId, LocalDateTime occurredAt) {
        UUID id = equipmentIncidentService.register(sourceEventId, equipmentId, institutionId, "가상 장애 사유",
                occurredAt, occurredAt, occurredAt.plusHours(2));
        createdIds.add(id);
        return id;
    }

    private long countBySourceEventId(UUID sourceEventId) {
        return equipmentIncidentRepository.findAll().stream()
                .filter(incident -> incident.getSourceEventId().equals(sourceEventId))
                .count();
    }

    @Test
    @DisplayName("새 이벤트는 OCCURRED 장애로 저장되고 입력 값이 그대로 기록된다")
    void registerNewIncident() {
        UUID sourceEventId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        UUID id = register(sourceEventId, equipmentId, institutionId, OCCURRED_AT);

        EquipmentIncident saved = equipmentIncidentRepository.findById(id).orElseThrow();
        assertThat(saved.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(saved.getEquipmentId()).isEqualTo(equipmentId);
        assertThat(saved.getInstitutionId()).isEqualTo(institutionId);
        assertThat(saved.getReason()).isEqualTo("가상 장애 사유");
        assertThat(saved.getStatus()).isEqualTo(EquipmentIncidentStatus.OCCURRED);
        assertThat(saved.getOccurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(saved.getStartAt()).isEqualTo(OCCURRED_AT);
        assertThat(saved.getEndAt()).isEqualTo(OCCURRED_AT.plusHours(2));
        assertThat(saved.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("같은 이벤트가 다시 오면 새로 만들지 않고 기존 장애 ID를 돌려주며 기존 행을 바꾸지 않는다")
    void duplicateEventIsIdempotent() {
        UUID sourceEventId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        UUID first = register(sourceEventId, equipmentId, institutionId, OCCURRED_AT);
        LocalDateTime updatedAt = equipmentIncidentRepository.findById(first).orElseThrow().getUpdatedAt();

        UUID second = register(sourceEventId, equipmentId, institutionId, OCCURRED_AT);

        assertThat(second).isEqualTo(first);
        assertThat(countBySourceEventId(sourceEventId)).isEqualTo(1);
        assertThat(equipmentIncidentRepository.findById(first).orElseThrow().getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("같은 이벤트 ID로 장비가 다른 장애가 오면 EQUIPMENT_INCIDENT_EVENT_CONFLICT이고 기존 장애는 그대로다")
    void sameEventIdWithOtherContent() {
        UUID sourceEventId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        UUID first = register(sourceEventId, equipmentId, institutionId, OCCURRED_AT);

        assertThatThrownBy(() -> register(sourceEventId, UUID.randomUUID(), institutionId, OCCURRED_AT))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EQUIPMENT_INCIDENT_EVENT_CONFLICT);

        assertThat(countBySourceEventId(sourceEventId)).isEqualTo(1);
        assertThat(equipmentIncidentRepository.findById(first).orElseThrow().getEquipmentId()).isEqualTo(equipmentId);
    }

    @Test
    @DisplayName("나노초가 있는 시각은 마이크로초로 맞춰 저장되고, 같은 값으로 다시 와도 같은 이벤트로 판단한다")
    void nanosecondInputIsIdempotent() {
        UUID sourceEventId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        LocalDateTime occurredAt = OCCURRED_AT.withNano(123_456_789);

        UUID first = register(sourceEventId, equipmentId, institutionId, occurredAt);
        UUID second = register(sourceEventId, equipmentId, institutionId, occurredAt);

        assertThat(second).isEqualTo(first);
        assertThat(equipmentIncidentRepository.findById(first).orElseThrow().getOccurredAt())
                .isEqualTo(occurredAt.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    @DisplayName("같은 이벤트가 10개 스레드에서 동시에 등록돼도 장애는 1건이고 모두 같은 ID를 받는다")
    void concurrentDuplicateEvents() throws Exception {
        UUID sourceEventId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<UUID>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.await();
                    return equipmentIncidentService.register(sourceEventId, equipmentId, institutionId,
                            "가상 장애 사유", OCCURRED_AT, OCCURRED_AT, OCCURRED_AT.plusHours(2));
                }));
            }
            ready.countDown();

            Set<UUID> ids = new HashSet<>();
            for (Future<UUID> result : results) {
                ids.add(result.get());
            }
            createdIds.addAll(ids);

            assertThat(ids).hasSize(1);
            assertThat(countBySourceEventId(sourceEventId)).isEqualTo(1);
        } finally {
            executor.shutdown();
        }
    }
}
