package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.service.IncidentProcessingService;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "itda.incident-processing.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class IncidentProcessingScheduler {

    private final IncidentReferralRepository incidentReferralRepository;
    private final IncidentProcessingService incidentProcessingService;
    private final int batchSize;

    public IncidentProcessingScheduler(
            IncidentReferralRepository incidentReferralRepository,
            IncidentProcessingService incidentProcessingService,
            @Value("${itda.incident-processing.scheduler.batch-size:50}") int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다.");
        }

        this.incidentReferralRepository = incidentReferralRepository;
        this.incidentProcessingService = incidentProcessingService;
        this.batchSize = batchSize;
    }

    // 이전 실행이 끝난 후 일정 시간 뒤 다시 실행
    @Scheduled(
            fixedDelayString =
                    "${itda.incident-processing.scheduler.fixed-delay-ms:5000}",
            initialDelayString =
                    "${itda.incident-processing.scheduler.initial-delay-ms:5000}"
    )
    public void processReadyTasks() {

        // 1. 이번 회차에 처리 가능한 작업 조회
        List<UUID> taskIds;

        try {
            taskIds = incidentReferralRepository.findReadyTaskIds(
                    LocalDateTime.now(),
                    IncidentProcessingStatus.PENDING,
                    IncidentProcessingStatus.FAILED,
                    IncidentProcessingStatus.PROCESSING,
                    PageRequest.of(0, batchSize)
            );
        } catch (RuntimeException e) {
            log.error("장애 후속 처리 작업 조회 실패", e);
            return;
        }

        if (taskIds.isEmpty()) {
            return;
        }

        log.info("장애 후속 처리 시작. 대상 작업 수={}", taskIds.size());

        // 2. 작업별로 독립적으로 처리
        for (UUID taskId : taskIds) {
            try {
                IncidentProcessingService.Outcome outcome =
                        incidentProcessingService.process(taskId);

                log.debug(
                        "장애 후속 처리 결과. taskId={}, outcome={}",
                        taskId,
                        outcome
                );

            } catch (RuntimeException e) {
                // 한 작업에서 예외가 나더라도 다른 작업은 계속 처리
                log.error(
                        "장애 후속 처리 중 예상하지 못한 오류. taskId={}",
                        taskId,
                        e
                );
            }
        }

        log.info("장애 후속 처리 회차 종료. 조회 작업 수={}", taskIds.size());
    }
}
