package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.service.IncidentProcessingService;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@SpringJUnitConfig(
        classes = IncidentProcessingSchedulerActivationTest.TestConfig.class
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/itda_referral_test",
        "itda.incident-processing.scheduler.enabled=true",
        "itda.incident-processing.scheduler.initial-delay-ms=100",
        "itda.incident-processing.scheduler.fixed-delay-ms=100",
        "itda.incident-processing.scheduler.batch-size=5"
})
class IncidentProcessingSchedulerActivationTest { // Spring의 스케줄링 기능이 정상적으로 연결됐는지 테스트

    @Configuration
    @EnableScheduling
    @Import(IncidentProcessingScheduler.class)
    static class TestConfig {
    }

    @MockitoBean
    private IncidentReferralRepository incidentReferralRepository;

    @MockitoBean
    private IncidentProcessingService incidentProcessingService;

    @Test
    @DisplayName("스케줄러 활성화 시 작업 조회 메서드가 자동 실행된다")
    void schedulerRunsAutomatically() {

        verify(incidentReferralRepository,
                timeout(3000).atLeastOnce()
        ).findReadyTaskIds(
                any(LocalDateTime.class),
                eq(IncidentProcessingStatus.PENDING),
                eq(IncidentProcessingStatus.FAILED),
                eq(IncidentProcessingStatus.PROCESSING),
                eq(PageRequest.of(0, 5))
        );
    }
}
