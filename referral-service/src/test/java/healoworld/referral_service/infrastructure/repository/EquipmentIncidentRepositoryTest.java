package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 테스트 전용 PostgreSQL(itda_referral_test)에 연결해 제약 조건과 조회를 검증한다
// 각 테스트는 테스트 트랜잭션 안에서 실행되고 끝나면 롤백되므로 DB에 데이터가 남지 않는다
@DataJpaTest
@Import(QuerydslConfig.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EquipmentIncidentRepositoryTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 2, 9, 0);

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    private EquipmentIncident incident(UUID sourceEventId, UUID equipmentId, LocalDateTime occurredAt) {
        return EquipmentIncident.occur(sourceEventId, equipmentId, UUID.randomUUID(), "테스트용 가상 장애 사유",
                occurredAt, occurredAt, occurredAt.plusHours(2));
    }

    private EquipmentIncident save(UUID sourceEventId, UUID equipmentId, LocalDateTime occurredAt) {
        return equipmentIncidentRepository.saveAndFlush(incident(sourceEventId, equipmentId, occurredAt));
    }

    @Test
    @DisplayName("장애는 OCCURRED·재시도 0회로 저장되고 생성·수정 시각이 기록된다")
    void saveOccurred() {
        UUID equipmentId = UUID.randomUUID();
        EquipmentIncident saved = save(UUID.randomUUID(), equipmentId, BASE);

        EquipmentIncident found = equipmentIncidentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(EquipmentIncidentStatus.OCCURRED);
        assertThat(found.getEquipmentId()).isEqualTo(equipmentId);
        assertThat(found.getStartAt()).isEqualTo(BASE);
        assertThat(found.getEndAt()).isEqualTo(BASE.plusHours(2));
        assertThat(found.getRetryCount()).isZero();
        assertThat(found.getNextRetryAt()).isNull();
        assertThat(found.getLastError()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 source_event_id로 두 번째 장애를 저장하면 UNIQUE 제약으로 거부된다 (중복 이벤트)")
    void sourceEventIdIsUnique() {
        UUID sourceEventId = UUID.randomUUID();
        save(sourceEventId, UUID.randomUUID(), BASE);

        assertThatThrownBy(() -> save(sourceEventId, UUID.randomUUID(), BASE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("source_event_id로 이미 저장된 이벤트인지 확인할 수 있다")
    void findBySourceEventId() {
        UUID sourceEventId = UUID.randomUUID();
        EquipmentIncident saved = save(sourceEventId, UUID.randomUUID(), BASE);

        assertThat(equipmentIncidentRepository.existsBySourceEventId(sourceEventId)).isTrue();
        assertThat(equipmentIncidentRepository.existsBySourceEventId(UUID.randomUUID())).isFalse();
        assertThat(equipmentIncidentRepository.findBySourceEventId(sourceEventId))
                .get().extracting(EquipmentIncident::getId).isEqualTo(saved.getId());
    }

    // PostgreSQL은 한 트랜잭션에서 오류가 나면 이후 SQL을 모두 거부하므로 컬럼마다 별도 테스트로 실행한다
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"source_event_id", "equipment_id", "institution_id", "reason", "occurred_at",
            "start_at", "end_at"})
    @DisplayName("필수 컬럼이 없으면 NOT NULL 제약으로 거부된다")
    void requiredColumns(String column) {
        UUID sourceEventId = column.equals("source_event_id") ? null : UUID.randomUUID();
        UUID equipmentId = column.equals("equipment_id") ? null : UUID.randomUUID();
        UUID institutionId = column.equals("institution_id") ? null : UUID.randomUUID();
        String reason = column.equals("reason") ? null : "가상 사유";
        LocalDateTime occurredAt = column.equals("occurred_at") ? null : BASE;
        LocalDateTime startAt = column.equals("start_at") ? null : BASE;
        LocalDateTime endAt = column.equals("end_at") ? null : BASE.plusHours(1);

        assertThatThrownBy(() -> equipmentIncidentRepository.saveAndFlush(EquipmentIncident.occur(
                sourceEventId, equipmentId, institutionId, reason, occurredAt, startAt, endAt)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("장비 ID로 해당 장비의 장애 이력만 최신순으로 페이지 조회한다")
    void findByEquipmentId() {
        UUID equipmentId = UUID.randomUUID();
        EquipmentIncident first = save(UUID.randomUUID(), equipmentId, BASE);
        EquipmentIncident second = save(UUID.randomUUID(), equipmentId, BASE.plusDays(1));
        EquipmentIncident third = save(UUID.randomUUID(), equipmentId, BASE.plusDays(2));
        save(UUID.randomUUID(), UUID.randomUUID(), BASE.plusDays(3)); // 다른 장비

        Page<EquipmentIncident> page = equipmentIncidentRepository.findByEquipmentId(equipmentId,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "occurredAt")));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(EquipmentIncident::getId)
                .containsExactly(third.getId(), second.getId());
        assertThat(first.getId()).isNotIn(page.getContent().stream().map(EquipmentIncident::getId).toList());
    }

    @Test
    @DisplayName("저장한 시각은 마이크로초 정밀도로 그대로 다시 읽힌다")
    void timestampPrecision() {
        LocalDateTime occurredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        EquipmentIncident saved = save(UUID.randomUUID(), UUID.randomUUID(), occurredAt);
        equipmentIncidentRepository.flush();

        assertThat(equipmentIncidentRepository.findById(saved.getId()).orElseThrow().getOccurredAt())
                .isEqualTo(occurredAt);
    }
}
