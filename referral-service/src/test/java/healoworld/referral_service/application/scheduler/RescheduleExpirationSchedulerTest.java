package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.service.RescheduleExpirationService;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RescheduleExpirationSchedulerTest {
    @Mock ReferralRepository referralRepository;
    @Mock RescheduleExpirationService service;

    @Test
    void queriesKoreanTimeCutoffAndContinuesAfterFailure() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        var scheduler = new RescheduleExpirationScheduler(referralRepository, service, clock, 50, 48);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(referralRepository.findRescheduleExpirationCandidates(
                LocalDateTime.of(2026, 11, 10, 12, 0), PageRequest.of(0, 50)))
                .thenReturn(List.of(first, second));
        when(service.expireIfOverdue(first)).thenThrow(new IllegalStateException("test"));
        scheduler.expireOverdueReferrals();
        verify(service).expireIfOverdue(first);
        verify(service).expireIfOverdue(second);
    }
}
