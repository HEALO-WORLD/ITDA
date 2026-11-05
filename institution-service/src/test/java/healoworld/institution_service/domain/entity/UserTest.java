package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class UserTest {

    @ParameterizedTest(name = "{0} 기관 -> {1}")
    @CsvSource({"REQUESTER, REQUESTER", "EXAM, EXAMINER"})
    @DisplayName("가입하면 역할은 소속기관 유형으로 정해지고 승인 대기 상태다.")
    void signup(InstitutionType type, Role expectedRole) {
        Institution institution = Institution.create("테스트기관", type, "서울특별시 중구 세종대로 1", "11100001");

        User user = User.signup("new_user", "encoded-password", institution);

        assertThat(user.getRole()).isEqualTo(expectedRole);
        assertThat(user.isActive()).isFalse();
        assertThat(user.getInstitution()).isSameAs(institution);
    }

    private User newUser() {
        Institution institution = Institution.create("A의원", InstitutionType.REQUESTER, "서울특별시 강남구 테헤란로 101", "11100001");
        return User.signup("new_user", "encoded-password", institution);
    }

    @Test
    @DisplayName("승인하면 활성 상태가 된다.")
    void approve() {
        User user = newUser();
        user.approve();
        assertThat(user.isActive()).isTrue();
    }

    @Test
    @DisplayName("이미 승인된 사용자를 다시 승인하면 예외")
    void approve_twice() {
        User user = newUser();
        user.approve();

        assertThatThrownBy(user::approve)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.USER_ALREADY_APPROVED);
    }
}
