package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(QuerydslConfig.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IncidentReferralRepositoryIntegrationTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 11, 3, 19, 0);

    @Autowired
    private IncidentReferralRepository incidentReferralRepository;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Test
    @DisplayName("처리 가능한 PENDING, FAILED, PROCESSING 작업만 조회한다")
    void findReadyTaskIds() {

        // 1. 장애 이력 생성
        EquipmentIncident incident = equipmentIncidentRepository.saveAndFlush(
                EquipmentIncident.occur(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "스케줄러 통합 테스트",
                        NOW.minusHours(1),
                        NOW.minusHours(1),
                        NOW.plusHours(1)
                )
        );

        // 2. 각 상태별 테스트 작업 생성
        UUID pending = createTask(incident);
        UUID failedReady = createTask(incident);
        UUID failedWaiting = createTask(incident);
        UUID processingExpired = createTask(incident);
        UUID processingActive = createTask(incident);
        UUID succeeded = createTask(incident);

        // FAILED - 재시도 시각이 지남
        moveToFailed(failedReady, NOW.minusMinutes(1));

        // FAILED - 재시도 시각이 아직 안 됨
        moveToFailed(failedWaiting, NOW.plusMinutes(10));

        // PROCESSING - 잠금 만료
        assertThat(incidentReferralRepository.claim(
                processingExpired,
                NOW.minusMinutes(10),
                NOW.minusMinutes(5)
        )).isEqualTo(1);

        // PROCESSING - 잠금 유효
        assertThat(incidentReferralRepository.claim(
                processingActive,
                NOW,
                NOW.plusMinutes(5)
        )).isEqualTo(1);

        // SUCCEEDED - 처리 완료
        LocalDateTime lockedUntil = NOW.plusMinutes(5);

        assertThat(incidentReferralRepository.claim(
                succeeded, NOW, lockedUntil
        )).isEqualTo(1);

        assertThat(incidentReferralRepository.markSucceeded(
                succeeded,
                lockedUntil,
                "RESCHEDULE_REQUIRED",
                NOW
        )).isEqualTo(1);

        // 3. 실행 가능한 작업 조회
        List<UUID> readyTaskIds =
                incidentReferralRepository.findReadyTaskIds(
                        NOW,
                        IncidentProcessingStatus.PENDING,
                        IncidentProcessingStatus.FAILED,
                        IncidentProcessingStatus.PROCESSING,
                        PageRequest.of(0, 100)
                );

        // 4. 조회 대상 검증
        assertThat(readyTaskIds)
                .contains(pending, failedReady, processingExpired)
                .doesNotContain(
                        failedWaiting,
                        processingActive,
                        succeeded
                );
    }

    // 테스트 작업 생성
    private UUID createTask(EquipmentIncident incident) {
        IncidentReferral task = IncidentReferral.pending(
                incident,
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        return incidentReferralRepository.saveAndFlush(task).getId();
    }

    // FAILED 상태 및 재시도 시각 설정
    private void moveToFailed(UUID taskId, LocalDateTime nextRetryAt) {
        LocalDateTime lockedUntil = NOW.plusMinutes(5);

        assertThat(incidentReferralRepository.claim(
                taskId, NOW, lockedUntil
        )).isEqualTo(1);

        assertThat(incidentReferralRepository.markFailed(
                taskId,
                lockedUntil,
                "[RETRYABLE] 테스트 실패",
                nextRetryAt,
                NOW
        )).isEqualTo(1);
    }

    @Test
    @DisplayName("FAILED 작업은 재시도 시각이 지나야 다시 선점할 수 있다")
    void failedTaskRespectsRetryTime() {

        // 1. 장애 및 후속 처리 작업 준비
        EquipmentIncident incident =
                equipmentIncidentRepository.saveAndFlush(
                        EquipmentIncident.occur(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "재시도 시각 검증",
                                NOW.minusHours(1),
                                NOW.minusHours(1),
                                NOW.plusHours(1)
                        )
                );

        UUID taskId = createTask(incident);

        // 2. 최초 작업 선점
        LocalDateTime firstLockUntil = NOW.plusMinutes(5);

        assertThat(incidentReferralRepository.claim(
                taskId, NOW, firstLockUntil
        )).isEqualTo(1);

        // 3. 처리 실패: 10분 후 재시도하도록 기록
        LocalDateTime retryAt = NOW.plusMinutes(10);

        assertThat(incidentReferralRepository.markFailed(
                taskId,
                firstLockUntil,
                "[RETRYABLE] 테스트 실패",
                retryAt,
                NOW
        )).isEqualTo(1);

        // 4. 재시도 시각 1분 전 → 선점 실패
        assertThat(incidentReferralRepository.claim(
                taskId,
                NOW.plusMinutes(9),
                NOW.plusMinutes(14)
        )).isZero();

        // 실패 상태 유지 확인
        assertThat(incidentReferralRepository.findById(taskId)
                .orElseThrow()
                .getProcessingStatus())
                .isEqualTo(IncidentProcessingStatus.FAILED);

        // 5. 재시도 시각 도달 → 선점 성공
        assertThat(incidentReferralRepository.claim(
                taskId,
                retryAt,
                retryAt.plusMinutes(5)
        )).isEqualTo(1);

        // 6. 상태 및 재시도 횟수 확인
        IncidentReferral retriedTask =
                incidentReferralRepository.findById(taskId).orElseThrow();

        assertThat(retriedTask.getProcessingStatus())
                .isEqualTo(IncidentProcessingStatus.PROCESSING);

        assertThat(retriedTask.getRetryCount()).isEqualTo(1);
    }
}
