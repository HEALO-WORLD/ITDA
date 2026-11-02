package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.ExaminationResult;
import healoworld.referral_service.global.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
class ExaminationResultRepositoryTest {

    @Autowired
    private ExaminationRepository examinationRepository;

    @Autowired
    private ExaminationResultRepository examinationResultRepository;

    private Examination saveExamination() {
        return examinationRepository.saveAndFlush(
                Examination.schedule(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    @DisplayName("결과는 summary·notes·registered_at과 함께 저장되고 검사 ID로 존재 여부를 확인할 수 있다")
    void saveResult() {
        Examination examination = saveExamination();

        ExaminationResult saved = examinationResultRepository.saveAndFlush(
                ExaminationResult.register(examination, "가상 결과 요약", "가상 추가 소견"));

        ExaminationResult found = examinationResultRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getSummary()).isEqualTo("가상 결과 요약");
        assertThat(found.getNotes()).isEqualTo("가상 추가 소견");
        assertThat(found.getRegisteredAt()).isEqualTo(saved.getRegisteredAt());
        assertThat(examinationResultRepository.existsByExaminationId(examination.getId())).isTrue();
        assertThat(examinationResultRepository.existsByExaminationId(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("notes 없이도 저장된다")
    void notesIsOptional() {
        ExaminationResult saved = examinationResultRepository.saveAndFlush(
                ExaminationResult.register(saveExamination(), "가상 결과 요약", null));

        assertThat(examinationResultRepository.findById(saved.getId()).orElseThrow().getNotes()).isNull();
    }

    @Test
    @DisplayName("summary가 없으면 NOT NULL 제약으로 거부된다")
    void summaryIsRequired() {
        ExaminationResult result = ExaminationResult.register(saveExamination(), null, null);

        assertThatThrownBy(() -> examinationResultRepository.saveAndFlush(result))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 검사에 두 번째 결과를 저장하면 examination_id UNIQUE 제약으로 거부된다")
    void examinationIdIsUnique() {
        Examination examination = saveExamination();
        examinationResultRepository.saveAndFlush(ExaminationResult.register(examination, "첫 결과", null));

        assertThatThrownBy(() -> examinationResultRepository.saveAndFlush(
                ExaminationResult.register(examination, "두 번째 결과", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("여러 검사 중 결과가 등록된 검사 ID만 조회한다")
    void findRegisteredExaminationIds() {
        // given
        Examination examination1 = saveExamination();
        Examination examination2 = saveExamination();
        Examination examination3 = saveExamination();

        // 1번과 3번 검사에만 결과 등록
        examinationResultRepository.saveAndFlush(
                ExaminationResult.register(examination1, "첫 번째 결과", null)
        );

        examinationResultRepository.saveAndFlush(
                ExaminationResult.register(examination3, "세 번째 결과", null)
        );

        // when
        List<UUID> registeredIds =
                examinationResultRepository.findRegisteredExaminationIds(
                        List.of(
                                examination1.getId(),
                                examination2.getId(),
                                examination3.getId()
                        )
                );

        // then
        assertThat(registeredIds)
                .containsExactlyInAnyOrder(
                        examination1.getId(),
                        examination3.getId()
                );

        assertThat(registeredIds)
                .doesNotContain(examination2.getId());
    }

    @Test
    @DisplayName("결과가 등록되지 않은 검사만 조회하면 빈 목록을 반환한다")
    void findRegisteredExaminationIdsEmpty() {
        // given
        Examination examination1 = saveExamination();
        Examination examination2 = saveExamination();

        // 결과는 등록하지 않음

        // when
        List<UUID> registeredIds =
                examinationResultRepository.findRegisteredExaminationIds(
                        List.of(
                                examination1.getId(),
                                examination2.getId()
                        )
                );

        // then
        assertThat(registeredIds).isEmpty();
    }

    @Test
    @DisplayName("조회 대상에 포함되지 않은 검사의 결과는 반환하지 않는다")
    void findRegisteredExaminationIdsExcludesOthers() {
        // given
        Examination targetExamination = saveExamination();
        Examination otherExamination = saveExamination();

        // 조회 대상이 아닌 검사에만 결과 등록
        examinationResultRepository.saveAndFlush(
                ExaminationResult.register(
                        otherExamination,
                        "다른 검사 결과",
                        null
                )
        );

        // when
        List<UUID> registeredIds =
                examinationResultRepository.findRegisteredExaminationIds(
                        List.of(targetExamination.getId())
                );

        // then
        assertThat(registeredIds).isEmpty();
    }
}
