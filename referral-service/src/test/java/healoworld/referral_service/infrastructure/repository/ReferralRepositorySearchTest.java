package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReferralRepositorySearchTest extends RepositoryTestSupport {
    private static final UUID INSTITUTION_A = UUID.randomUUID();
    private static final UUID INSTITUTION_B = UUID.randomUUID();
    private static final UUID USER_A1 = UUID.randomUUID();
    private static final UUID USER_A2 = UUID.randomUUID();
    private static final UUID USER_B1 = UUID.randomUUID();

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private EntityManager em;

    private Referral a1Referral;
    private Referral a2Referral;
    private Referral b1Referral;

    @BeforeEach
    void setUp() {
        a1Referral = save(INSTITUTION_A, USER_A1, "김철수");
        a2Referral = save(INSTITUTION_A, USER_A2, "이영희");
        b1Referral = save(INSTITUTION_B, USER_B1, "김민수");
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("일반 사용자는 본인 기관의 의뢰만 조회된다")
    void userSeesOwnInstitutionOnly() {
        Page<Referral> result = search(condition(null, null, null), INSTITUTION_A, RoleType.USER);

        assertThat(ids(result)).containsExactlyInAnyOrder(a1Referral.getId(), a2Referral.getId());
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("일반 사용자가 referrerId로 검색하면 본인 기관 안에서 해당 작성자 의뢰만 조회된다")
    void userFiltersByReferrer() {
        Page<Referral> result = search(condition(USER_A1, null, null), INSTITUTION_A, RoleType.USER);

        assertThat(ids(result)).containsExactly(a1Referral.getId());
    }

    @Test
    @DisplayName("다른 기관 작성자의 referrerId로 검색해도 다른 기관 의뢰는 노출되지 않는다")
    void userCannotSeeOtherInstitutionByReferrer() {
        Page<Referral> result = search(condition(USER_B1, null, null), INSTITUTION_A, RoleType.USER);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("관리자는 모든 기관의 의뢰를 조회하고 referrerId로 필터할 수 있다")
    void admin() {
        Page<Referral> all = search(condition(null, null, null), UUID.randomUUID(), RoleType.ADMIN);
        Page<Referral> filtered = search(condition(USER_B1, null, null), UUID.randomUUID(), RoleType.ADMIN);

        assertThat(ids(all)).containsExactlyInAnyOrder(a1Referral.getId(), a2Referral.getId(), b1Referral.getId());
        assertThat(ids(filtered)).containsExactly(b1Referral.getId());
    }

    @Test
    @DisplayName("환자명 부분 일치와 상태로 검색된다")
    void patientNameAndStatus() {
        Referral confirmed = referralRepository.findById(a2Referral.getId()).orElseThrow();
        confirmed.confirm();
        em.flush();
        em.clear();

        Page<Referral> byName = search(condition(null, null, "김"), INSTITUTION_A, RoleType.USER);
        Page<Referral> byStatus = search(condition(null, ReferralStatus.CONFIRMED, null), INSTITUTION_A, RoleType.USER);

        assertThat(ids(byName)).containsExactly(a1Referral.getId());
        assertThat(ids(byStatus)).containsExactly(a2Referral.getId());
    }

    @Test
    @DisplayName("soft delete된 의뢰는 조회되지 않는다")
    void excludesDeleted() {
        Referral failed = referralRepository.findById(a1Referral.getId()).orElseThrow();
        failed.fail();
        failed.delete(USER_A1);
        em.flush();
        em.clear();

        Page<Referral> result = search(condition(null, null, null), INSTITUTION_A, RoleType.USER);

        assertThat(ids(result)).containsExactly(a2Referral.getId());
    }

    private Referral save(UUID institutionId, UUID referrerId, String patientName) {
        return referralRepository.save(
                Referral.create(institutionId, referrerId, UUID.randomUUID(), patientName, UUID.randomUUID()));
    }

    private Page<Referral> search(ReferralSearchCondition cond, UUID institutionId, RoleType role) {
        return referralRepository.search(cond, institutionId, role, cond.toPageable());
    }

    private ReferralSearchCondition condition(UUID referrerId, ReferralStatus status, String patientName) {
        return new ReferralSearchCondition(status, null, referrerId, null, patientName, null, null, null);
    }

    private List<UUID> ids(Page<Referral> page) {
        return page.getContent().stream().map(Referral::getId).toList();
    }
}
