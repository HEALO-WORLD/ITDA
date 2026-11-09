package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationRepositorySearchTest extends RepositoryTestSupport {
    // 의뢰기관 A, B / 검사기관 X, Y
    private static final UUID REFERRING_A = UUID.randomUUID();
    private static final UUID REFERRING_B = UUID.randomUUID();
    private static final UUID EXAM_X = UUID.randomUUID();
    private static final UUID EXAM_Y = UUID.randomUUID();
    private static final UUID USER_A1 = UUID.randomUUID();
    private static final UUID USER_A2 = UUID.randomUUID();
    private static final UUID USER_B1 = UUID.randomUUID();

    private static final LocalDateTime DAY_1 = LocalDateTime.of(2026, 11, 2, 10, 0);
    private static final LocalDateTime DAY_2 = DAY_1.plusDays(1);
    private static final LocalDateTime DAY_3 = DAY_1.plusDays(2);

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EntityManager em;

    private Referral a1Referral;
    private Reservation a1AtX;
    private Reservation a2AtY;
    private Reservation b1AtX;

    @BeforeEach
    void setUp() {
        a1Referral = saveReferral(REFERRING_A, USER_A1);
        a1AtX = saveReservation(a1Referral, EXAM_X, DAY_1);
        a2AtY = saveReservation(saveReferral(REFERRING_A, USER_A2), EXAM_Y, DAY_2);
        b1AtX = saveReservation(saveReferral(REFERRING_B, USER_B1), EXAM_X, DAY_3);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("의뢰기관은 자기 기관 의뢰의 예약을 조회한다")
    void referringInstitution() {
        assertThat(ids(search(condition(), REFERRING_A, RoleType.REQUESTER)))
                .containsExactlyInAnyOrder(a1AtX.getId(), a2AtY.getId());
        assertThat(ids(search(condition(), REFERRING_B, RoleType.REQUESTER)))
                .containsExactly(b1AtX.getId());
    }

    @Test
    @DisplayName("검사기관은 자기 기관에서 진행하는 예약을 조회한다")
    void examInstitution() {
        assertThat(ids(search(condition(), EXAM_X, RoleType.EXAMINER)))
                .containsExactlyInAnyOrder(a1AtX.getId(), b1AtX.getId());
        assertThat(ids(search(condition(), EXAM_Y, RoleType.EXAMINER)))
                .containsExactly(a2AtY.getId());
    }

    @Test
    @DisplayName("관계없는 기관은 아무 예약도 조회되지 않는다")
    void unrelatedInstitution() {
        Page<Reservation> result = search(condition(), UUID.randomUUID(), RoleType.REQUESTER);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("referrerId 검색은 기관 범위 안에서만 적용된다")
    void referrerFilterWithinScope() {
        ReservationSearchCondition byA1 = condition(null, USER_A1, null, null);
        ReservationSearchCondition byB1 = condition(null, USER_B1, null, null);

        assertThat(ids(search(byA1, REFERRING_A, RoleType.REQUESTER))).containsExactly(a1AtX.getId());
        assertThat(ids(search(byB1, REFERRING_A, RoleType.REQUESTER))).isEmpty();
        assertThat(ids(search(byB1, EXAM_X, RoleType.EXAMINER))).containsExactly(b1AtX.getId());
    }

    @Test
    @DisplayName("관리자는 모든 예약을 조회한다")
    void admin() {
        Page<Reservation> result = search(condition(), UUID.randomUUID(), RoleType.ADMIN);

        assertThat(ids(result)).containsExactlyInAnyOrder(a1AtX.getId(), a2AtY.getId(), b1AtX.getId());
        assertThat(result.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("referralId로 한 의뢰의 예약 이력을 조회한다")
    void byReferral() {
        Reservation retry = saveReservation(referralRepository.findById(a1Referral.getId()).orElseThrow(), EXAM_Y, DAY_3);
        em.flush();
        em.clear();

        Page<Reservation> result = search(condition(a1Referral.getId(), null, null, null), REFERRING_A, RoleType.REQUESTER);

        assertThat(ids(result)).containsExactlyInAnyOrder(a1AtX.getId(), retry.getId());
    }

    @Test
    @DisplayName("검사일 기간으로 검색된다 (양 끝 포함)")
    void examDateRange() {
        Page<Reservation> result = search(condition(null, null, DAY_1, DAY_2), UUID.randomUUID(), RoleType.ADMIN);

        assertThat(ids(result)).containsExactlyInAnyOrder(a1AtX.getId(), a2AtY.getId());
    }

    private Referral saveReferral(UUID institutionId, UUID referrerId) {
        return referralRepository.save(
                Referral.create(institutionId, referrerId, UUID.randomUUID(), "홍길동", UUID.randomUUID()));
    }

    private Reservation saveReservation(Referral referral, UUID examInstitutionId, LocalDateTime examDate) {
        return reservationRepository.save(Reservation.create(referral, 1, UUID.randomUUID(), examInstitutionId,
                UUID.randomUUID(), examDate, examDate, examDate.plusHours(1)));
    }

    private Page<Reservation> search(ReservationSearchCondition cond, UUID institutionId, RoleType role) {
        return reservationRepository.search(cond, institutionId, role, cond.toPageable());
    }

    private ReservationSearchCondition condition() {
        return condition(null, null, null, null);
    }

    private ReservationSearchCondition condition(UUID referralId, UUID referrerId, LocalDateTime from, LocalDateTime to) {
        return new ReservationSearchCondition(referralId, referrerId, null, null, null, null, from, to, null, null, null);
    }

    private List<UUID> ids(Page<Reservation> page) {
        return page.getContent().stream().map(Reservation::getId).toList();
    }
}
