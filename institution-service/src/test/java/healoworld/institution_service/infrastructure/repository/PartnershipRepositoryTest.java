package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.support.PostgresContainerSupport;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static healoworld.institution_service.domain.enums.PartnershipStatus.ACTIVE;
import static healoworld.institution_service.domain.enums.PartnershipStatus.PENDING;
import static healoworld.institution_service.domain.enums.PartnershipStatus.REJECTED;
import static healoworld.institution_service.domain.enums.PartnershipStatus.TERMINATED;
import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest, @AutoConfigureTestDatabase, TestEntityManager는 InstitutionRepositoryTest와 같은 import
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class PartnershipRepositoryTest extends PostgresContainerSupport {

    @Autowired
    TestEntityManager em;

    @Autowired
    PartnershipRepository partnershipRepository;

    // 의료기관(요청) 2곳, 검사기관 2곳 → 쌍(A-X, A-Y, B-X, B-Y)을 나눠 써서 unique 제약을 피함
    private Institution reqA;
    private Institution reqB;
    private Institution examX;
    private Institution examY;

    @BeforeEach
    void setUp() {
        reqA = em.persist(Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));
        reqB = em.persist(Institution.create("B의원", InstitutionType.REQUESTER, "서울시 마포구", "10000002"));
        examX = em.persist(Institution.create("X영상센터", InstitutionType.EXAM, "서울시 서초구", "10000003"));
        examY = em.persist(Institution.create("Y영상센터", InstitutionType.EXAM, "서울시 송파구", "10000004"));
    }

    @Test
    @DisplayName("요청 기관 → 검사 기관 방향으로 특정 상태의 협약이 있는지 확인한다 (Referral 협약 확인).")
    void existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus() {
        // given
        em.persist(partnership(reqA, examX, ACTIVE));
        em.persist(partnership(reqA, examY, PENDING));
        flushAndClear();

        // when & then
        assertThat(partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus(
                reqA.getId(), examX.getId(), ACTIVE)).isTrue();
        assertThat(partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus(
                reqA.getId(), examY.getId(), ACTIVE)).isFalse();   // 상태가 다름
        assertThat(partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus(
                examX.getId(), reqA.getId(), ACTIVE)).isFalse();   // 방향을 바꾸면 없음
    }

    @Test
    @DisplayName("방향·상태 확인에 더해 삭제된 협약은 제외한다 (장비 조회 접근 권한).")
    void existsByRequesterInstitutionIdAndExamInstitutionIdAndStatusAndDeletedAtIsNull() {
        // given
        em.persist(partnership(reqA, examX, ACTIVE));
        em.persist(deleted(partnership(reqB, examX, ACTIVE)));
        flushAndClear();

        // when & then
        assertThat(partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatusAndDeletedAtIsNull(
                reqA.getId(), examX.getId(), ACTIVE)).isTrue();
        assertThat(partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatusAndDeletedAtIsNull(
                reqB.getId(), examX.getId(), ACTIVE)).isFalse();   // 삭제된 협약
        assertThat(partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatusAndDeletedAtIsNull(
                examX.getId(), reqA.getId(), ACTIVE)).isFalse();   // 방향을 바꾸면 없음
    }

    @Test
    @DisplayName("요청 기관과 특정 상태로 협약한 검사 기관 ID 목록을 조회한다 (Slot 협약 기관 조회).")
    void findExamInstitutionIds() {
        // given
        em.persist(partnership(reqA, examX, ACTIVE));
        em.persist(partnership(reqA, examY, PENDING));
        em.persist(partnership(reqB, examY, ACTIVE));   // 다른 요청 기관의 협약
        flushAndClear();

        // when
        List<java.util.UUID> activeOfA = partnershipRepository.findExamInstitutionIds(reqA.getId(), ACTIVE);
        List<java.util.UUID> pendingOfA = partnershipRepository.findExamInstitutionIds(reqA.getId(), PENDING);

        // then
        assertThat(activeOfA).containsExactly(examX.getId());     // 다른 요청 기관(B)의 ACTIVE 협약은 제외
        assertThat(pendingOfA).containsExactly(examY.getId());    // 상태 조건
    }

    @Test
    @DisplayName("내 협약 목록: 요청·검사 어느 쪽이든 조회, 상태가 없으면 전체, 요청일순, 양쪽 기관 함께 조회.")
    void findMyPartnerships() {
        // given: 저장 순서와 요청일 순서를 일부러 다르게
        Partnership aToX = em.persist(partnership(reqA, examX, ACTIVE, LocalDateTime.of(2026, 11, 3, 9, 0)));
        Partnership aToY = em.persist(partnership(reqA, examY, PENDING, LocalDateTime.of(2026, 11, 1, 9, 0)));
        Partnership bToX = em.persist(partnership(reqB, examX, PENDING, LocalDateTime.of(2026, 11, 2, 9, 0)));
        em.persist(partnership(reqB, examY, ACTIVE, LocalDateTime.of(2026, 11, 4, 9, 0)));   // A·X와 무관한 협약
        flushAndClear();

        // when
        List<Partnership> allOfA = partnershipRepository.findMyPartnerships(reqA.getId(), null);
        List<Partnership> allOfX = partnershipRepository.findMyPartnerships(examX.getId(), null);
        List<Partnership> pendingOfA = partnershipRepository.findMyPartnerships(reqA.getId(), PENDING);

        // then
        assertThat(allOfA).extracting(Partnership::getId)
                .containsExactly(aToY.getId(), aToX.getId());     // 요청 기관 쪽, 요청일순
        assertThat(allOfX).extracting(Partnership::getId)
                .containsExactly(bToX.getId(), aToX.getId());     // 검사 기관 쪽에서도 조회됨
        assertThat(pendingOfA).extracting(Partnership::getId)
                .containsExactly(aToY.getId());                   // 상태 조건
        assertThat(allOfA).allMatch(p -> Hibernate.isInitialized(p.getRequesterInstitution())
                && Hibernate.isInitialized(p.getExamInstitution()));   // join fetch
    }

    @Test
    @DisplayName("기관이 어느 쪽이든 진행 중(요청·활성) 협약이 있는지 확인한다: 끝난 협약·삭제된 협약 제외 (기관 삭제 차단).")
    void existsByInstitutionIdAndStatusIn() {
        // given
        em.persist(partnership(reqA, examX, ACTIVE));
        em.persist(partnership(reqB, examY, TERMINATED));
        em.persist(deleted(partnership(reqB, examX, PENDING)));
        flushAndClear();

        // when & then
        assertThat(partnershipRepository.existsByInstitutionIdAndStatusIn(
                reqA.getId(), PartnershipStatus.inProgress())).isTrue();    // 요청 기관 쪽
        assertThat(partnershipRepository.existsByInstitutionIdAndStatusIn(
                examX.getId(), PartnershipStatus.inProgress())).isTrue();   // 검사 기관 쪽
        assertThat(partnershipRepository.existsByInstitutionIdAndStatusIn(
                reqB.getId(), PartnershipStatus.inProgress())).isFalse();   // 종료 + 삭제된 협약뿐
        assertThat(partnershipRepository.existsByInstitutionIdAndStatusIn(
                examY.getId(), PartnershipStatus.inProgress())).isFalse();  // 종료된 협약뿐
    }

    @Test
    @DisplayName("두 기관 사이에 진행 중 협약이 있는지 양방향으로 확인한다: 끝난 협약·삭제된 협약 제외 (기관 상세 접근 권한).")
    void existsBetweenInstitutionsAndStatusIn() {
        // given
        em.persist(partnership(reqA, examX, ACTIVE));
        em.persist(partnership(reqA, examY, REJECTED));
        em.persist(deleted(partnership(reqB, examY, PENDING)));
        flushAndClear();

        // when & then
        assertThat(partnershipRepository.existsBetweenInstitutionsAndStatusIn(
                reqA.getId(), examX.getId(), PartnershipStatus.inProgress())).isTrue();
        assertThat(partnershipRepository.existsBetweenInstitutionsAndStatusIn(
                examX.getId(), reqA.getId(), PartnershipStatus.inProgress())).isTrue();   // 방향을 바꿔도 있음
        assertThat(partnershipRepository.existsBetweenInstitutionsAndStatusIn(
                reqA.getId(), examY.getId(), PartnershipStatus.inProgress())).isFalse();  // 거절된 협약
        assertThat(partnershipRepository.existsBetweenInstitutionsAndStatusIn(
                reqB.getId(), examY.getId(), PartnershipStatus.inProgress())).isFalse();  // 삭제된 협약
    }

    @Test
    @DisplayName("두 기관의 기존 협약을 상태와 상관없이 조회한다: 방향이 다르면 없음 (협약 재요청).")
    void findByRequesterInstitutionIdAndExamInstitutionId() {
        // given
        Partnership terminated = em.persist(partnership(reqA, examX, TERMINATED));
        flushAndClear();

        // when
        Optional<Partnership> found = partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(
                reqA.getId(), examX.getId());
        Optional<Partnership> reversed = partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(
                examX.getId(), reqA.getId());
        Optional<Partnership> none = partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(
                reqA.getId(), examY.getId());

        // then
        assertThat(found).map(Partnership::getId).hasValue(terminated.getId());   // 종료된 협약도 조회 (재요청 대상)
        assertThat(found).map(Partnership::getStatus).hasValue(TERMINATED);
        assertThat(reversed).isEmpty();
        assertThat(none).isEmpty();
    }

    // ── 데이터 준비 도우미 ──

    // 엔티티의 상태 변경 메서드를 순서대로 호출해서 원하는 상태를 만든다
    private Partnership partnership(Institution requester, Institution exam, PartnershipStatus status) {
        Partnership partnership = Partnership.request(requester, exam);   // PENDING
        switch (status) {
            case PENDING -> { }
            case ACTIVE -> partnership.approve();
            case REJECTED -> partnership.reject();
            case TERMINATED -> {
                partnership.approve();
                partnership.terminate();
            }
        }
        return partnership;
    }

    // 정렬 테스트용: 요청일을 직접 지정 (requestedAt은 일반 컬럼이라 저장 전에 바꿔도 됨)
    private Partnership partnership(Institution requester, Institution exam, PartnershipStatus status,
                                    LocalDateTime requestedAt) {
        Partnership partnership = partnership(requester, exam, status);
        ReflectionTestUtils.setField(partnership, "requestedAt", requestedAt);
        return partnership;
    }

    // Partnership에는 삭제 메서드가 없어서 deletedAt에 직접 값을 넣는다
    private Partnership deleted(Partnership partnership) {
        ReflectionTestUtils.setField(partnership, "deletedAt", LocalDateTime.now());
        return partnership;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}