package healoworld.referral_service.presentation.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Controller 노출 전이므로 @Valid가 적용할 Bean Validation 규칙만 직접 검증한다
class ExaminationResultCreateRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("summary가 있으면 notes 없이도 통과한다")
    void valid() {
        assertThat(validator.validate(new ExaminationResultCreateRequest("요약", null))).isEmpty();
    }

    @Test
    @DisplayName("summary가 없거나 빈 문자열·공백이면 실패한다")
    void summaryRequired() {
        assertThat(validator.validate(new ExaminationResultCreateRequest(null, "소견"))).hasSize(1);
        assertThat(validator.validate(new ExaminationResultCreateRequest("", null))).hasSize(1);
        assertThat(validator.validate(new ExaminationResultCreateRequest("   ", null))).hasSize(1);
    }
}
