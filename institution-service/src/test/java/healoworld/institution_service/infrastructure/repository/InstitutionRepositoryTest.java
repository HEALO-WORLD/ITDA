package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.support.PostgresContainerSupport;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)  // 메모리 DB로 바꾸지 말고 컨테이너 PostgreSQL 사용
@ActiveProfiles("test")  // 테스트 설정(ddl create-drop, 시드 끔) 사용
public class InstitutionRepositoryTest extends PostgresContainerSupport {

    @Autowired
    TestEntityManager em;

    @Autowired
    InstitutionRepository institutionRepository;

    // 조건이 여러개라 실수하기 쉬움
    @Test
    @DisplayName("가입용 공개 기관 목록: 활성/미삭제 의료,검사기관만 조회하고, 운영사/삭제/비활성 기관은 제외한다.")
    void findAllByActiveTrueAndDeletedAtIsNullAndTypeNot() {
        // 포함되어야 하는 기관(조건을 모두 만족)
        Institution requester = save(Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));
        Institution exam = save(Institution.create("B영상센터", InstitutionType.EXAM, "서울시 서초구", "10000002"));

        // 조건 하나마다 "그 조건 때문에 걸러져야 하는" 기관을 하나씩
        save(Institution.create("운영사", InstitutionType.PLATFORM, "서울시 중구", null));

        Institution deleted = Institution.create("C의원", InstitutionType.REQUESTER, "서울시 마포구", "10000003");
        deleted.delete(UUID.randomUUID());
        save(deleted);

        Institution inactive = Institution.create("D영상센터", InstitutionType.EXAM, "서울시 송파구", "10000004");
        ReflectionTestUtils.setField(inactive, "active", false);
        save(inactive);

        em.flush();  // DB에 실제로 insert
        em.clear();  // 메모리(영속성컨텍스트)를 비워서 아래 조회가 DB에서 진짜로 실행되게 함

        List<Institution> result = institutionRepository.findAllByActiveTrueAndDeletedAtIsNullAndTypeNot(InstitutionType.PLATFORM);

        assertThat(result).extracting(Institution::getId).containsExactlyInAnyOrder(requester.getId(), exam.getId());
    }
    /*
    // 요양기관기호 중복 확인
    boolean existsByCareInstitutionCode(String careInstitutionCode);

    // 삭제 안 된 기관 전체 조회
    List<Institution> findAllByDeletedAtIsNullOrderByCreatedAtDesc();*/

    @Test
    @DisplayName("삭제된 기관은 ID로 조회되지 않는다.")
    void findByIdAndDeletedAtIsNull() {
        // given
        Institution active = save(Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));

        Institution deleted = Institution.create("C의원", InstitutionType.REQUESTER, "서울시 마포구", "10000003");
        deleted.delete(UUID.randomUUID());
        save(deleted);

        em.flush();  // DB에 실제로 insert
        em.clear();  // 메모리(영속성컨텍스트)를 비워서 아래 조회가 DB에서 진짜로 실행되게 함

        // when
        Optional<Institution> foundActive = institutionRepository.findByIdAndDeletedAtIsNull(active.getId());
        Optional<Institution> foundDeleted = institutionRepository.findByIdAndDeletedAtIsNull(deleted.getId());

        // then
        assertThat(foundActive).map(Institution::getId).hasValue(active.getId());
        assertThat(foundDeleted).isEmpty();
    }

    @Test
    @DisplayName("삭제된 기관의 요양기관기호도 존재하는 것으로 본다.(재등록 불가)")
    void existsByCareInstitutionCode() {
        // given
        save(Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));

        Institution deleted = Institution.create("C의원", InstitutionType.REQUESTER, "서울시 마포구", "10000003");
        deleted.delete(UUID.randomUUID());
        save(deleted);

        em.flush();
        em.clear();

        // when & then
        assertThat(institutionRepository.existsByCareInstitutionCode("10000001")).isTrue();    // 정상 기관의 기호
        assertThat(institutionRepository.existsByCareInstitutionCode("10000003")).isTrue();    // 삭제된 기관의 기호도 true
        assertThat(institutionRepository.existsByCareInstitutionCode("99999999")).isFalse();   // 없는 기호
    }

    @Test
    @DisplayName("삭제된 기관은 제외하고 최근 등록순으로 조회한다.")
    void findAllByDeletedAtIsNullOrderByCreatedAtDesc() {
        // given: 저장 순서와 등록 시간 순서를 일부러 다르게 둔다
        Institution oldest = save(Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));
        Institution newest = save(Institution.create("B영상센터", InstitutionType.EXAM, "서울시 서초구", "10000002"));
        Institution middle = save(Institution.create("C의원", InstitutionType.REQUESTER, "서울시 마포구", "10000003"));

        Institution deleted = Institution.create("D영상센터", InstitutionType.EXAM, "서울시 송파구", "10000004");
        deleted.delete(UUID.randomUUID());
        save(deleted);

        em.flush();

        // 등록 시간을 직접 지정 (삭제된 기관을 가장 최근으로 → 삭제 필터가 없으면 맨 앞에 끼어듦)
        setCreatedAt(oldest, LocalDateTime.of(2026, 11, 1, 9, 0));
        setCreatedAt(middle, LocalDateTime.of(2026, 11, 2, 9, 0));
        setCreatedAt(newest, LocalDateTime.of(2026, 11, 3, 9, 0));
        setCreatedAt(deleted, LocalDateTime.of(2026, 11, 4, 9, 0));

        em.clear();

        // when
        List<Institution> result = institutionRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        // then: 순서까지 정확히
        assertThat(result).extracting(Institution::getId)
                .containsExactly(newest.getId(), middle.getId(), oldest.getId());
    }

    // createdAt은 JPA로 수정할 수 없어서(updatable = false) 테스트에서만 SQL로 직접 바꾼다
    private void setCreatedAt(Institution institution, LocalDateTime createdAt) {
        em.getEntityManager()
                .createNativeQuery("update p_institutions set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", institution.getId())
                .executeUpdate();
    }

    private Institution save(Institution institution) {
        return em.persist(institution);
    }
}
