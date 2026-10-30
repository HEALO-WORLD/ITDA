package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralHistoryRepositoryTest extends RepositoryTestSupport {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 2, 10, 0);

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReferralHistoryRepository referralHistoryRepository;

    @Autowired
    private EntityManager em;

    @Test
    @DisplayName("한 의뢰의 변경 내역만 발생 시각 오름차순으로 조회된다")
    void findByReferralOrderedByOccurredAt() {
        Referral referral = saveReferral();
        Reservation reservation = saveReservation(referral);
        Referral other = saveReferral();
        Reservation otherReservation = saveReservation(other);

        // 저장 순서와 발생 시각 순서를 다르게 둔다
        ReferralHistory confirmed = save(referral, reservation, ReferralStatus.PENDING, ReferralStatus.CONFIRMED, Reason.RESERVATION_CONFIRMED, T0.plusMinutes(5));
        ReferralHistory created = save(referral, reservation, null, ReferralStatus.PENDING, Reason.REFERRAL_CREATED, T0);
        save(other, otherReservation, null, ReferralStatus.PENDING, Reason.REFERRAL_CREATED, T0.plusMinutes(1));
        em.flush();
        em.clear();

        List<ReferralHistory> result = referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referral.getId());

        assertThat(result).extracting(ReferralHistory::getId).containsExactly(created.getId(), confirmed.getId());
        assertThat(result.get(0).getFromStatus()).isNull();
    }

    @Test
    @DisplayName("변경 내역이 없으면 빈 목록")
    void empty() {
        assertThat(referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(UUID.randomUUID())).isEmpty();
    }

    private Referral saveReferral() {
        return referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "홍길동", UUID.randomUUID()));
    }

    private Reservation saveReservation(Referral referral) {
        return reservationRepository.save(Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), T0, T0, T0.plusHours(1)));
    }

    private ReferralHistory save(Referral referral, Reservation reservation, ReferralStatus from, ReferralStatus to,
                                 Reason reason, LocalDateTime occurredAt) {
        ReferralHistory history = ReferralHistory.create(referral, reservation, from, to, reason, UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(history, "occurredAt", occurredAt);
        return referralHistoryRepository.save(history);
    }
}
