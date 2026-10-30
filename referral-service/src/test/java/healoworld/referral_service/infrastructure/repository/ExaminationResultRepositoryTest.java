package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.ExaminationResult;
import healoworld.referral_service.global.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;

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
}
