package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.outbox.OutboxEvent;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 정합성 점검 대상 조회 쿼리(시간 조건, 상태 고정, outbox 미발행 제외, id 커서)를 실제 DB로 검증한다
class ReconciliationTargetQueryTest extends RepositoryTestSupport {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 9, 12, 0);
    private static final LocalDateTime FUTURE_START = NOW.plusDays(3);
    private static final LocalDateTime PAST_START = NOW.minusDays(1);
    private static final UUID MIN_ID = new UUID(0L, 0L);
    private static final PageRequest ALL = PageRequest.of(0, 100);

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private EntityManager em;

    @Nested
    @DisplayName("findStaleInProgress")
    class StaleInProgress {
        private final LocalDateTime updatedBefore = NOW.minusMinutes(10);

        @Test
        @DisplayName("기준 시각보다 오래 멈춘 REQUESTED·HELD만 조회한다")
        void onlyStaleRequestedAndHeld() {
            UUID staleRequested = reservation(ReservationStatus.REQUESTED, FUTURE_START, NOW.minusMinutes(30), null);
            UUID staleHeld = reservation(ReservationStatus.HELD, FUTURE_START, NOW.minusMinutes(30), null);
            reservation(ReservationStatus.REQUESTED, FUTURE_START, NOW.minusMinutes(1), null);   // 아직 정상 처리 중
            reservation(ReservationStatus.CONFIRMED, FUTURE_START, NOW.minusMinutes(30), null);  // 진행 중 상태 아님

            assertThat(ids(reservationRepository.findStaleInProgress(updatedBefore, MIN_ID, ALL)))
                    .containsExactlyInAnyOrder(staleRequested, staleHeld);
        }

        @Test
        @DisplayName("발행되지 않은 outbox가 남은 예약은 제외하고, 발행되면 다시 포함한다")
        void excludesUnpublishedOutbox() {
            UUID reservationId = reservation(ReservationStatus.REQUESTED, FUTURE_START, NOW.minusMinutes(30), null);
            OutboxEvent outbox = outbox(reservationId);

            assertThat(reservationRepository.findStaleInProgress(updatedBefore, MIN_ID, ALL)).isEmpty();

            outboxEventRepository.markPublished(outbox.getId(), NOW);
            em.clear();

            assertThat(ids(reservationRepository.findStaleInProgress(updatedBefore, MIN_ID, ALL)))
                    .containsExactly(reservationId);
        }

        @Test
        @DisplayName("id 순으로 정렬되고, lastId 다음부터 개수 제한만큼 조회한다")
        void cursorPaging() {
            for (int i = 0; i < 3; i++) {
                reservation(ReservationStatus.REQUESTED, FUTURE_START, NOW.minusMinutes(30), null);
            }
            List<UUID> all = ids(reservationRepository.findStaleInProgress(updatedBefore, MIN_ID, ALL));
            assertThat(all).hasSize(3);

            List<UUID> firstPage = ids(reservationRepository.findStaleInProgress(updatedBefore, MIN_ID, PageRequest.of(0, 2)));
            List<UUID> secondPage = ids(reservationRepository.findStaleInProgress(updatedBefore, firstPage.getLast(), PageRequest.of(0, 2)));

            assertThat(firstPage).containsExactly(all.get(0), all.get(1));
            assertThat(secondPage).containsExactly(all.get(2));
        }
    }

    @Nested
    @DisplayName("findUpcomingConfirmed")
    class UpcomingConfirmed {

        @Test
        @DisplayName("시작 전인 CONFIRMED만 조회한다")
        void onlyUpcomingConfirmed() {
            UUID upcoming = reservation(ReservationStatus.CONFIRMED, FUTURE_START, NOW.minusMinutes(30), null);
            reservation(ReservationStatus.CONFIRMED, PAST_START, NOW.minusMinutes(30), null);    // 이미 지난 예약
            reservation(ReservationStatus.HELD, FUTURE_START, NOW.minusMinutes(30), null);       // 확정 아님

            assertThat(ids(reservationRepository.findUpcomingConfirmed(NOW, MIN_ID, ALL)))
                    .containsExactly(upcoming);
        }
    }

    @Nested
    @DisplayName("findRecentlyEnded")
    class RecentlyEnded {
        private final LocalDateTime endedAfter = NOW.minusHours(24);

        @Test
        @DisplayName("조회 기간 안에 끝났고 슬롯 시간이 아직 남은 FAILED·BLOCKED·CANCELED만 조회한다")
        void onlyRecentlyEndedWithUpcomingSlot() {
            UUID failed = reservation(ReservationStatus.FAILED, FUTURE_START, NOW.minusHours(1), NOW.minusHours(1));
            UUID blocked = reservation(ReservationStatus.BLOCKED, FUTURE_START, NOW.minusHours(1), NOW.minusHours(1));
            UUID canceled = reservation(ReservationStatus.CANCELED, FUTURE_START, NOW.minusHours(1), NOW.minusHours(1));
            reservation(ReservationStatus.CANCELED, FUTURE_START, NOW.minusDays(3), NOW.minusDays(3));  // 조회 기간 밖
            reservation(ReservationStatus.CANCELED, PAST_START, NOW.minusHours(1), NOW.minusHours(1));  // 이미 지난 슬롯
            reservation(ReservationStatus.CONFIRMED, FUTURE_START, NOW.minusHours(1), null);            // 종료 상태 아님

            assertThat(ids(reservationRepository.findRecentlyEnded(NOW, endedAfter, MIN_ID, ALL)))
                    .containsExactlyInAnyOrder(failed, blocked, canceled);
        }
    }

    // 상태 전이 메서드로 예약을 만든 뒤, 감사 필드·종료 시각은 네이티브 UPDATE로 원하는 값에 맞춘다
    private UUID reservation(ReservationStatus status, LocalDateTime startTime, LocalDateTime updatedAt, LocalDateTime endedAt) {
        Referral referral = referralRepository.saveAndFlush(
                Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "홍길동", UUID.randomUUID()));
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                startTime, startTime, startTime.plusHours(1));
        switch (status) {
            case REQUESTED -> {
            }
            case HELD -> reservation.hold();
            case CONFIRMED -> {
                reservation.hold();
                reservation.confirm();
            }
            case FAILED -> reservation.fail(FailureReason.SLOT_CONFLICT);
            case BLOCKED -> reservation.block();
            case CANCELED -> {
                reservation.hold();
                reservation.confirm();
                reservation.cancel();
            }
        }
        UUID id = reservationRepository.saveAndFlush(reservation).getId();

        em.createNativeQuery("update p_reservations set updated_at = :updatedAt, ended_at = :endedAt where id = :id")
                .setParameter("updatedAt", updatedAt)
                .setParameter("endedAt", endedAt)
                .setParameter("id", id)
                .executeUpdate();
        em.clear();
        return id;
    }

    private OutboxEvent outbox(UUID reservationId) {
        OutboxEvent outbox = OutboxEvent.create(UUID.randomUUID(), "RESERVATION", reservationId,
                "slot-commands", UUID.randomUUID().toString(), "HoldRequested", "{}");
        OutboxEvent saved = outboxEventRepository.saveAndFlush(outbox);
        em.clear();
        return saved;
    }

    private static List<UUID> ids(List<ReconciliationTarget> targets) {
        return targets.stream().map(ReconciliationTarget::reservationId).toList();
    }
}
