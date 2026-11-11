package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.domain.enums.InstitutionType;
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

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest, @AutoConfigureTestDatabase, TestEntityManager는 InstitutionRepositoryTest와 같은 import
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest extends PostgresContainerSupport {

    @Autowired
    TestEntityManager em;

    @Autowired
    UserRepository userRepository;

    private Institution requester;
    private Institution exam;

    @BeforeEach
    void setUp() {
        // 사용자는 소속 기관이 있어야 저장할 수 있어서 기관을 먼저 준비
        requester = em.persist(Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));
        exam = em.persist(Institution.create("B영상센터", InstitutionType.EXAM, "서울시 서초구", "10000002"));
    }

    @Test
    @DisplayName("삭제된 사용자의 아이디도 존재하는 것으로 본다 (재가입 불가).")
    void existsByLoginId() {
        // given
        em.persist(approved("user01", requester));
        em.persist(deleted(approved("deleted01", requester)));
        flushAndClear();

        // when & then
        assertThat(userRepository.existsByLoginId("user01")).isTrue();      // 정상 사용자
        assertThat(userRepository.existsByLoginId("deleted01")).isTrue();   // 삭제된 사용자의 아이디도 true
        assertThat(userRepository.existsByLoginId("nobody")).isFalse();     // 없는 아이디
    }

    @Test
    @DisplayName("로그인 조회: 삭제된 사용자는 조회되지 않고, 승인 대기 사용자는 조회된다.")
    void findByLoginIdAndDeletedAtIsNull() {
        // given
        User active = em.persist(approved("user01", requester));
        User pending = em.persist(pending("pending01", requester));
        em.persist(deleted(approved("deleted01", requester)));
        flushAndClear();

        // when
        Optional<User> foundActive = userRepository.findByLoginIdAndDeletedAtIsNull("user01");
        Optional<User> foundPending = userRepository.findByLoginIdAndDeletedAtIsNull("pending01");
        Optional<User> foundDeleted = userRepository.findByLoginIdAndDeletedAtIsNull("deleted01");

        // then
        assertThat(foundActive).map(User::getId).hasValue(active.getId());
        assertThat(foundPending).map(User::getId).hasValue(pending.getId());   // 승인 여부는 서비스가 판단
        assertThat(foundDeleted).isEmpty();
    }

    @Test
    @DisplayName("활성 여부로 사용자 목록을 조회한다: 삭제 제외, 최근 가입순, 기관 함께 조회.")
    void findAllByActiveAndDeletedAtIsNullOrderByCreatedAtDesc() {
        // given: 저장 순서와 가입 시간 순서를 일부러 다르게
        User approvedOld = em.persist(approved("approved01", requester));
        User pendingNew = em.persist(pending("pending02", exam));
        User approvedNew = em.persist(approved("approved02", exam));
        User pendingOld = em.persist(pending("pending01", requester));
        User deletedApproved = em.persist(deleted(approved("deleted01", requester)));
        em.flush();

        setCreatedAt(approvedOld, LocalDateTime.of(2026, 11, 1, 9, 0));
        setCreatedAt(pendingOld, LocalDateTime.of(2026, 11, 2, 9, 0));
        setCreatedAt(approvedNew, LocalDateTime.of(2026, 11, 3, 9, 0));
        setCreatedAt(pendingNew, LocalDateTime.of(2026, 11, 4, 9, 0));
        setCreatedAt(deletedApproved, LocalDateTime.of(2026, 11, 5, 9, 0));   // 가장 최근 → 삭제 필터가 없으면 맨 앞에 끼어듦
        em.clear();

        // when
        List<User> activeUsers = userRepository.findAllByActiveAndDeletedAtIsNullOrderByCreatedAtDesc(true);
        List<User> pendingUsers = userRepository.findAllByActiveAndDeletedAtIsNullOrderByCreatedAtDesc(false);

        // then
        assertThat(activeUsers).extracting(User::getId)
                .containsExactly(approvedNew.getId(), approvedOld.getId());
        assertThat(pendingUsers).extracting(User::getId)
                .containsExactly(pendingNew.getId(), pendingOld.getId());
        assertThat(activeUsers).allMatch(user -> Hibernate.isInitialized(user.getInstitution()));   // @EntityGraph
    }

    @Test
    @DisplayName("사용자 전체 목록: 활성 여부와 상관없이 삭제만 제외하고 최근 가입순, 기관 함께 조회.")
    void findAllByDeletedAtIsNullOrderByCreatedAtDesc() {
        // given
        User approvedUser = em.persist(approved("approved01", requester));
        User pendingUser = em.persist(pending("pending01", exam));
        User deletedUser = em.persist(deleted(approved("deleted01", requester)));
        em.flush();

        setCreatedAt(approvedUser, LocalDateTime.of(2026, 11, 2, 9, 0));
        setCreatedAt(pendingUser, LocalDateTime.of(2026, 11, 1, 9, 0));
        setCreatedAt(deletedUser, LocalDateTime.of(2026, 11, 3, 9, 0));
        em.clear();

        // when
        List<User> result = userRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        // then
        assertThat(result).extracting(User::getId)
                .containsExactly(approvedUser.getId(), pendingUser.getId());
        assertThat(result).allMatch(user -> Hibernate.isInitialized(user.getInstitution()));
    }

    @Test
    @DisplayName("ID로 기관과 함께 조회한다: 삭제된 사용자는 조회되지 않는다.")
    void findWithInstitutionByIdAndDeletedAtIsNull() {
        // given
        User active = em.persist(approved("user01", requester));
        User deletedUser = em.persist(deleted(approved("deleted01", requester)));
        flushAndClear();

        // when
        Optional<User> foundActive = userRepository.findWithInstitutionByIdAndDeletedAtIsNull(active.getId());
        Optional<User> foundDeleted = userRepository.findWithInstitutionByIdAndDeletedAtIsNull(deletedUser.getId());

        // then
        assertThat(foundActive).map(User::getId).hasValue(active.getId());
        assertThat(Hibernate.isInitialized(foundActive.get().getInstitution())).isTrue();   // 기관이 함께 조회됨
        assertThat(foundDeleted).isEmpty();
    }

    @Test
    @DisplayName("소속 사용자 존재 확인: 삭제된 사용자만 있는 기관은 소속 사용자가 없는 것으로 본다.")
    void existsByInstitutionIdAndDeletedAtIsNull() {
        // given
        Institution onlyDeleted = em.persist(Institution.create("C의원", InstitutionType.REQUESTER, "서울시 마포구", "10000003"));
        em.persist(pending("pending01", requester));            // 승인 대기 사용자도 소속 사용자
        em.persist(deleted(approved("deleted01", onlyDeleted)));
        flushAndClear();

        // when & then
        assertThat(userRepository.existsByInstitutionIdAndDeletedAtIsNull(requester.getId())).isTrue();
        assertThat(userRepository.existsByInstitutionIdAndDeletedAtIsNull(onlyDeleted.getId())).isFalse();
        assertThat(userRepository.existsByInstitutionIdAndDeletedAtIsNull(exam.getId())).isFalse();   // 사용자 없음
    }

    // ── 데이터 준비 도우미 ──

    private User pending(String loginId, Institution institution) {
        return User.signup(loginId, "encoded-password", institution);   // 가입 직후 = 비활성
    }

    private User approved(String loginId, Institution institution) {
        User user = pending(loginId, institution);
        user.approve();
        return user;
    }

    // User에는 삭제 메서드가 없어서 deletedAt에 직접 값을 넣는다
    private User deleted(User user) {
        ReflectionTestUtils.setField(user, "deletedAt", LocalDateTime.now());
        return user;
    }

    // createdAt은 JPA로 수정할 수 없어서(updatable = false) 테스트에서만 SQL로 직접 바꾼다
    private void setCreatedAt(User user, LocalDateTime createdAt) {
        em.getEntityManager()
                .createNativeQuery("update p_users set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", user.getId())
                .executeUpdate();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}