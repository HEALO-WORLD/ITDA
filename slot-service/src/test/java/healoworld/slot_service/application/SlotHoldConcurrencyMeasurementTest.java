package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.ConcurrentHoldRunner;
import healoworld.slot_service.support.ConcurrentHoldRunner.RoundResult;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.IntSummaryStatistics;
import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "MEASURE", matches = "true")   // 환경변수 MEASURE가 true일 때만 실행
class SlotHoldConcurrencyMeasurementTest extends PostgresContainerSupport {

    private static final int WARM_UP = 1;   // 기록하지 않고 버리는 연습 회차 수
    private static final int ROUNDS = 10;   // 실제로 기록하는 회차 수

    @Autowired SlotHoldService slotHoldService;
    @Autowired SlotRepository slotRepository;
    @Autowired SlotStatusHistoryRepository slotStatusHistoryRepository;

    @ParameterizedTest(name = "스레드 {0}개")
    @ValueSource(ints = {10, 50, 100})
    void 측정(int threadCount) throws Exception {

        // 연습 회차
        for (int i = 0; i < WARM_UP; i++) {
            ConcurrentHoldRunner.run(slotHoldService, slotRepository, threadCount);
            slotStatusHistoryRepository.deleteAllInBatch();
            slotRepository.deleteAllInBatch();
        }

        // 기록 회차
        List<RoundResult> results = new ArrayList<>();
        for (int i = 0; i < ROUNDS; i++) {
            results.add(ConcurrentHoldRunner.run(slotHoldService, slotRepository, threadCount));
            slotStatusHistoryRepository.deleteAllInBatch();
            slotRepository.deleteAllInBatch();
        }

        // 통계
        IntSummaryStatistics success = results.stream()
                .mapToInt(RoundResult::success).summaryStatistics();   // 성공 횟수 통계
        int errors = results.stream().mapToInt(RoundResult::error).sum();   // 예외 수의 합
        double avgMs = results.stream().mapToLong(RoundResult::elapsedMs).average().orElse(0);   // 걸린 시간 평균

        System.out.printf("| %d | %.1f / %d | %d | %.1f |%n",
                threadCount, success.getAverage(), success.getMax(), errors, avgMs);
    }
}