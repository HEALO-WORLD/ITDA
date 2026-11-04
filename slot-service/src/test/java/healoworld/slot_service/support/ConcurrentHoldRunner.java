package healoworld.slot_service.support;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.support.fixture.SlotFixture;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ConcurrentHoldRunner {

    public record RoundResult(int success, int fail, int error, long elapsedMs) {}

    private ConcurrentHoldRunner() {}

    public static RoundResult run(SlotHoldService service,
                                  SlotRepository repository,
                                  int threadCount) throws InterruptedException {
        Slot slot = repository.saveAndFlush(SlotFixture.available());
        UUID slotId = slot.getId();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);   // 선수 N명
        CountDownLatch ready = new CountDownLatch(threadCount);   // 준비될 때마다 1씩 감소. 0이 되면 "전원 준비 완료"
        CountDownLatch start = new CountDownLatch(1);   // 한 번 줄이면 0이 되어 "출발!"
        CountDownLatch done = new CountDownLatch(threadCount);   // 끝날 때마다 1씩 감소. 0이 되면 "전원 도착"

        AtomicInteger success = new AtomicInteger();   // 성공 횟수
        AtomicInteger fail = new AtomicInteger();   // 정상 거절 횟수 (이미 잡힌 슬롯)
        AtomicInteger error = new AtomicInteger();   // 예외 터진 횟수

        try {
            for (int i = 0; i < threadCount; i++) {
                UUID reservationId = UUID.randomUUID();
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        HoldResult result = service.hold(slotId, reservationId, UUID.randomUUID());
                        if (result.isSuccess()) success.incrementAndGet();
                        else fail.incrementAndGet();
                    } catch (Exception e) {
                        error.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            ready.await();                       // 모든 스레드가 출발선에 설 때까지 대기
            long begin = System.nanoTime();
            start.countDown();                   // 동시에 출발
            if (!done.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 요청이 30초 안에 끝나지 않았습니다");
            }
            long elapsedMs = (System.nanoTime() - begin) / 1_000_000;   // 걸린 시간

            return new RoundResult(success.get(), fail.get(), error.get(), elapsedMs);
        } finally {
            executor.shutdownNow();
        }
    }
}