package healoworld.institution_service.application.service.partnership;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipHistoryRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import healoworld.institution_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시 요청은 각 요청이 실제로 커밋해야 unique 제약 충돌이 일어나서 테스트 트랜잭션을 쓰지 않는다.
 * 대신 테스트가 끝나면 직접 데이터를 지운다.
 *
 */
@SpringBootTest
@ActiveProfiles("test")
public class PartnershipConcurrencyIntegrationTest extends PostgresContainerSupport {
    @Autowired
    PartnershipService partnershipService;
    @Autowired
    InstitutionRepository institutionRepository;
    @Autowired
    PartnershipRepository partnershipRepository;
    @Autowired
    PartnershipHistoryRepository partnershipHistoryRepository;

    private Institution requester;
    private Institution exam;

    @BeforeEach
    void setUp() {
        requester = institutionRepository.save(
                Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));
        exam = institutionRepository.save(
                Institution.create("X영상센터", InstitutionType.EXAM, "서울시 서초구", "10000003"));
    }

    @AfterEach
    void cleanUp() {
        //외래키 때문에 이력 -> 협약 -> 기관 순서로 삭제
        partnershipHistoryRepository.deleteAllInBatch();
        partnershipRepository.deleteAllInBatch();
        institutionRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("같은 두 기관이 동시에 협약을 요청하면 하나만 생성되고, 나머지는 PARTNERSHIP_ALREADY_IN_PROGRESS로 거절된다.")
    void request_concurrent() throws Exception {
        int requestCount = 5;
        ExecutorService executorService = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            futures.add(executorService.submit(() -> {
                ready.countDown();
                start.await();  // 모든 스레드가 준비되면 동시에 출발
                return partnershipService.request(requester.getId(), exam.getId());
            }));
        }
        ready.await();
        start.countDown();

        int successCount = 0;
        List<Throwable> failures = new ArrayList<>();
        for (Future<?> future : futures) {
            try {
                future.get();
                successCount++;
            } catch (ExecutionException e) {
                failures.add(e.getCause());  // 스레드 안에서 난 실제 예외
            }
        }
        executorService.shutdown();

        assertThat(successCount).isEqualTo(1);
        assertThat(failures).hasSize(requestCount - 1)
                .allSatisfy(e -> assertThat(e)
                        .isInstanceOf(BaseException.class)
                        .extracting("errorCode").isEqualTo(ErrorCode.PARTNERSHIP_ALREADY_IN_PROGRESS));
        assertThat(partnershipRepository.count()).isEqualTo(1);  // 협약은 하나
        assertThat(partnershipHistoryRepository.count()).isEqualTo(1);  // 이력도 첫 요청 하나
    }
}
