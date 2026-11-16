package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.service.RescheduleExpirationService;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "itda.reschedule-expiration.scheduler", name = "enabled", havingValue = "true")
public class RescheduleExpirationScheduler {
    private final ReferralRepository referralRepository;
    private final RescheduleExpirationService expirationService;
    private final Clock clock;
    private final int batchSize;
    private final long graceHours;

    public RescheduleExpirationScheduler(ReferralRepository referralRepository,
                                         RescheduleExpirationService expirationService,
                                         Clock clock,
                                         @Value("${itda.reschedule-expiration.scheduler.batch-size:50}") int batchSize,
                                         @Value("${itda.reschedule-expiration.scheduler.grace-hours:48}") long graceHours) {
        if (batchSize < 1 || graceHours < 0) {
            throw new IllegalArgumentException("batch-size는 1 이상, grace-hours는 0 이상이어야 합니다.");
        }
        this.referralRepository = referralRepository;
        this.expirationService = expirationService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.graceHours = graceHours;
    }

    @Scheduled(fixedDelayString = "${itda.reschedule-expiration.scheduler.fixed-delay-ms:3600000}",
               initialDelayString = "${itda.reschedule-expiration.scheduler.initial-delay-ms:60000}")
    public void expireOverdueReferrals() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(graceHours);
        List<UUID> ids;
        try {
            ids = referralRepository.findRescheduleExpirationCandidates(cutoff, PageRequest.of(0, batchSize));
        } catch (RuntimeException e) {
            log.error("재조정 만료 후보 조회 실패", e);
            return;
        }
        for (UUID referralId : ids) {
            try {
                if (expirationService.expireIfOverdue(referralId)) {
                    log.info("재조정 기한 만료 의뢰 종료. referralId={}", referralId);
                }
            } catch (RuntimeException e) {
                // 각 의뢰는 별도 트랜잭션. 한 건 실패해도 나머지 대상은 계속 처리한다.
                log.error("재조정 기한 만료 처리 실패. referralId={}", referralId, e);
            }
        }
    }
}
