package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.service.IncidentProcessingService;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentProcessingSchedulerUnitTest {  // 스케줄러의 기능 자체를 테스트
    private static final int BATCH_SIZE = 50;
    // 한국 시간 2026-11-10 09:30 (UTC 00:30). JVM 기본 시간대와 관계없이 이 시각으로 조회해야 한다
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T00:30:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private IncidentReferralRepository incidentReferralRepository;

    @Mock
    private IncidentProcessingService incidentProcessingService;

    private IncidentProcessingScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new IncidentProcessingScheduler(
                incidentReferralRepository,
                incidentProcessingService,
                BATCH_SIZE,
                CLOCK
        );
    }

    // Repository에서 반환할 작업 ID 설정
    private void givenReadyTasks(List<UUID> taskIds, int batchSize) {
        when(incidentReferralRepository.findReadyTaskIds(
                any(LocalDateTime.class),
                eq(IncidentProcessingStatus.PENDING),
                eq(IncidentProcessingStatus.FAILED),
                eq(IncidentProcessingStatus.PROCESSING),
                eq(PageRequest.of(0, batchSize))
        )).thenReturn(taskIds);
    }

    @Test
    @DisplayName("처리할 작업이 없으면 process를 호출하지 않는다")
    void noReadyTasks() {
        givenReadyTasks(List.of(), BATCH_SIZE);

        scheduler.processReadyTasks();

        verifyNoInteractions(incidentProcessingService);
    }

    @Test
    @DisplayName("조회된 모든 작업을 순서대로 처리한다")
    void processesAllReadyTasks() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        givenReadyTasks(List.of(first, second, third), BATCH_SIZE);

        scheduler.processReadyTasks();

        InOrder inOrder = inOrder(incidentProcessingService);

        inOrder.verify(incidentProcessingService).process(first);
        inOrder.verify(incidentProcessingService).process(second);
        inOrder.verify(incidentProcessingService).process(third);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("하나의 작업에서 예외가 발생해도 다음 작업을 처리한다")
    void continuesAfterTaskFailure() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        givenReadyTasks(List.of(first, second), BATCH_SIZE);

        when(incidentProcessingService.process(first))
                .thenThrow(new IllegalStateException("테스트 오류"));

        scheduler.processReadyTasks();

        verify(incidentProcessingService).process(first);
        verify(incidentProcessingService).process(second);
    }

    @Test
    @DisplayName("설정된 배치 크기로 작업을 조회한다")
    void usesConfiguredBatchSize() {
        int customBatchSize = 10;

        scheduler = new IncidentProcessingScheduler(
                incidentReferralRepository,
                incidentProcessingService,
                customBatchSize,
                CLOCK
        );

        givenReadyTasks(List.of(UUID.randomUUID()), customBatchSize);

        scheduler.processReadyTasks();

        verify(incidentReferralRepository).findReadyTaskIds(
                any(LocalDateTime.class),
                eq(IncidentProcessingStatus.PENDING),
                eq(IncidentProcessingStatus.FAILED),
                eq(IncidentProcessingStatus.PROCESSING),
                eq(PageRequest.of(0, customBatchSize))
        );
    }

    @Test
    @DisplayName("작업 조회에 실패하면 후속 처리를 실행하지 않는다")
    void stopsWhenQueryFails() {
        when(incidentReferralRepository.findReadyTaskIds(
                any(LocalDateTime.class),
                eq(IncidentProcessingStatus.PENDING),
                eq(IncidentProcessingStatus.FAILED),
                eq(IncidentProcessingStatus.PROCESSING),
                eq(PageRequest.of(0, BATCH_SIZE))
        )).thenThrow(new IllegalStateException("DB 조회 실패"));

        scheduler.processReadyTasks();

        verifyNoInteractions(incidentProcessingService);
    }

    @Test
    @DisplayName("처리 가능 작업은 한국 시간 현재 시각을 기준으로 조회한다")
    void queriesReadyTasksWithKoreanTime() {
        givenReadyTasks(List.of(), BATCH_SIZE);

        scheduler.processReadyTasks();

        verify(incidentReferralRepository).findReadyTaskIds(
                eq(LocalDateTime.of(2026, 11, 10, 9, 30)),
                eq(IncidentProcessingStatus.PENDING),
                eq(IncidentProcessingStatus.FAILED),
                eq(IncidentProcessingStatus.PROCESSING),
                eq(PageRequest.of(0, BATCH_SIZE))
        );
    }
}
