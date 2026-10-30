package healoworld.referral_service.domain.enums;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ExaminationStatusTest {

    // 현재 상태에서 요청 상태로 변경할 수 있는지 검증한다.
    // 전체 9가지 조합을 테스트해 잘못된 상태 전이를 방지한다.
    @ParameterizedTest(name = "{0} → {1}: {2}")
    @CsvSource({
            "SCHEDULED, SCHEDULED, false",
            "SCHEDULED, IN_PROGRESS, true",
            "SCHEDULED, COMPLETED, false",

            "IN_PROGRESS, SCHEDULED, false",
            "IN_PROGRESS, IN_PROGRESS, false",
            "IN_PROGRESS, COMPLETED, true",

            "COMPLETED, SCHEDULED, false",
            "COMPLETED, IN_PROGRESS, false",
            "COMPLETED, COMPLETED, false"
    })
    void canTransitionTo(
            ExaminationStatus current,
            ExaminationStatus target,
            boolean expected
    ) {
        boolean result = current.canTransitionTo(target);

        assertThat(result).isEqualTo(expected);
    }
}