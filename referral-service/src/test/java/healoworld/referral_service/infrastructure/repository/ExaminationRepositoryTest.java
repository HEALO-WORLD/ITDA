package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.global.config.QuerydslConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Import(QuerydslConfig.class)
class ExaminationRepositoryTest {

    @Autowired
    private ExaminationRepository examinationRepository;

    // 테스트용 검사 데이터 생성
    private Examination createExamination(UUID institutionId) {

        Examination examination = Examination.schedule(
                UUID.randomUUID(), // 의뢰 ID
                UUID.randomUUID(), // 예약 ID
                institutionId      // 검사기관 ID
        );

        return examinationRepository.saveAndFlush(examination);
    }

    @Test
    @DisplayName("검사기관에 속한 검사 목록을 조회한다")
    void findByExamInstitutionId_success() {

        // given
        UUID institutionId = UUID.randomUUID();

        createExamination(institutionId);
        createExamination(institutionId);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(2);

        assertThat(result.getContent())
                .allMatch(examination ->
                        examination.getExamInstitutionId()
                                .equals(institutionId)
                );
    }

    @Test
    @DisplayName("다른 검사기관의 검사는 조회되지 않는다")
    void findByExamInstitutionId_excludesOtherInstitution() {

        // given
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        Examination examinationA = createExamination(institutionA);
        Examination examinationB = createExamination(institutionB);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionA,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);

        assertThat(result.getContent())
                .extracting(Examination::getId)
                .containsExactly(examinationA.getId())
                .doesNotContain(examinationB.getId());
    }

    @Test
    @DisplayName("소속 기관에 검사가 없으면 빈 페이지를 반환한다")
    void findByExamInstitutionId_empty() {

        // given
        UUID institutionA = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        createExamination(institutionB);

        // when
        Page<Examination> result =
                examinationRepository.findByExamInstitutionId(
                        institutionA,
                        PageRequest.of(0, 10)
                );

        // then
        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getContent()).isEmpty();
    }

    @Test
    @DisplayName("검사 목록은 페이지네이션을 지원한다")
    void findByExamInstitutionId_pagination() {

        // given
        UUID institutionId = UUID.randomUUID();

        createExamination(institutionId);
        createExamination(institutionId);
        createExamination(institutionId);

        // when
        Page<Examination> firstPage =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(0, 2)
                );

        Page<Examination> secondPage =
                examinationRepository.findByExamInstitutionId(
                        institutionId,
                        PageRequest.of(1, 2)
                );

        // then
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);

        assertThat(firstPage.getNumber()).isZero();
        assertThat(secondPage.getNumber()).isEqualTo(1);
    }
}