package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.application.scheduler.HoldExpiryScheduler;
import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.ExpiredHold;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// 테스트 프로필은 스케줄러 빈을 끄므로(hold-expiry.enabled=false) 직접 만들어 호출한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class HoldExpirySchedulerTest extends PostgresContainerSupport {

    private static final LocalDateTime NOW = FixedClockConfig.NOW;
    private static final int BATCH_SIZE = 100;   // HoldExpiryScheduler.BATCH_SIZE와 같은 값

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotHoldExpiryService slotHoldExpiryService;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private Clock clock;

    private HoldExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new HoldExpiryScheduler(slotRepository, slotHoldExpiryService, clock);
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void 만료_대상이_한_묶음보다_많으면_한_회차에_모두_만료한다() {
        int count = BATCH_SIZE * 2 + 50;
        slotRepository.saveAllAndFlush(IntStream.range(0, count)
                .mapToObj(i -> SlotFixture.held(UUID.randomUUID(), NOW.minusMinutes(1)))
                .toList());

        scheduler.expireHolds();

        assertThat(slotRepository.findAll()).extracting(Slot::getStatus).containsOnly(SlotStatus.AVAILABLE);
        assertThat(outboxEventRepository.count()).isEqualTo(count);
    }

    @Test
    void 만료되지_않은_HOLD는_그대로_둔다() {
        Slot expired = slotRepository.saveAndFlush(SlotFixture.held(UUID.randomUUID(), NOW.minusMinutes(1)));
        Slot valid = slotRepository.saveAndFlush(SlotFixture.held(UUID.randomUUID(), NOW.plusMinutes(1)));

        scheduler.expireHolds();

        assertThat(slotRepository.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(slotRepository.findById(valid.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    void 한_묶음을_모두_처리하지_못하면_다음_묶음으로_넘어가지_않고_회차를_끝낸다() {
        // 같은 실패 슬롯만 계속 조회되는 상황에서 반복이 끝나는지 본다
        SlotRepository repository = mock(SlotRepository.class);
        SlotHoldExpiryService failingService = mock(SlotHoldExpiryService.class);
        List<ExpiredHold> stuck = IntStream.range(0, BATCH_SIZE)
                .mapToObj(i -> new ExpiredHold(UUID.randomUUID(), UUID.randomUUID(), NOW.minusMinutes(1)))
                .toList();
        given(repository.findExpiredHolds(any(SlotStatus.class), any(LocalDateTime.class), any(Pageable.class)))
                .willReturn(stuck);
        given(failingService.expire(any(), any(), any())).willThrow(new RuntimeException("DB 오류"));

        new HoldExpiryScheduler(repository, failingService, clock).expireHolds();

        verify(repository, times(1)).findExpiredHolds(any(SlotStatus.class), any(LocalDateTime.class), any(Pageable.class));
        verify(failingService, times(BATCH_SIZE)).expire(any(), any(), any());
    }
}
