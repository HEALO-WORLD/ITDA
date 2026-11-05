package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


public class InstitutionTest {
    private static final String ADDRESS = "서울특별시 종로구 종로 404";

    @Test
    @DisplayName("의료기관을 만들면 바로 활성화 상태다")
    void create_medical() {
        Institution institution = Institution.create("의원", InstitutionType.REQUESTER, ADDRESS, "11100004");

        assertThat(institution.isActive()).isTrue();
        assertThat(institution.getType().isMedical()).isTrue();
        assertThat(institution.getCareInstitutionCode()).isEqualTo("11100004");
    }

    private void assertErrorCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "이름 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("이름이 비어 있으면 예외")
    void create_blankName(String name) {
        assertErrorCode(() -> Institution.create(name, InstitutionType.REQUESTER, ADDRESS, "11100004"),
                ErrorCode.INVALID_INSTITUTION_NAME);
    }

    @Test
    @DisplayName("이름이 100자를 넘으면 예외")
    void create_longName() {
        String name = "가".repeat(101);

        assertErrorCode(() -> Institution.create(name, InstitutionType.REQUESTER, ADDRESS, "11100004"),
                ErrorCode.INVALID_INSTITUTION_NAME);
    }

    @ParameterizedTest(name = "주소 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("주소가 비어 있으면 예외")
    void create_blankAddress(String address) {
        assertErrorCode(() -> Institution.create("C의원", InstitutionType.REQUESTER, address, "11100004"),
                ErrorCode.INVALID_INSTITUTION_ADDRESS);
    }

    @ParameterizedTest(name = "요양기관기호 [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"1110000", "111000044", "1110000A"})
    @DisplayName("의료기관의 요양기관기호가 숫자 8자리가 아니면 예외")
    void create_invalidCode(String code) {
        assertErrorCode(() -> Institution.create("C의원", InstitutionType.EXAM, ADDRESS, code),
                ErrorCode.INVALID_CARE_INSTITUTION_CODE);
    }

    @Test
    @DisplayName("운영사가 요양기관기호를 가지면 예외")
    void create_platformWithCode() {
        assertErrorCode(() -> Institution.create("운영사", InstitutionType.PLATFORM, ADDRESS, "11100009"),
                ErrorCode.INVALID_CARE_INSTITUTION_CODE);
    }

    @Test
    @DisplayName("이름과 주소를 수정할 수 있다")
    void update() {
        Institution institution = Institution.create("C의원", InstitutionType.REQUESTER, ADDRESS, "11100004");

        institution.update("C내과의원", "서울특별시 종로구 종로 405");

        assertThat(institution.getName()).isEqualTo("C내과의원");
        assertThat(institution.getAddress()).isEqualTo("서울특별시 종로구 종로 405");
    }

    @Test
    @DisplayName("수정할 때도 불변식을 검사한다")
    void update_blankAddress() {
        Institution institution = Institution.create("C의원", InstitutionType.REQUESTER, ADDRESS, "11100004");

        assertErrorCode(() -> institution.update("C내과의원", ""), ErrorCode.INVALID_INSTITUTION_ADDRESS);
    }

    @Test
    @DisplayName("삭제하면 비활성이 되고 삭제 시각과 삭제자가 기록된다 (논리 삭제)")
    void delete() {
        Institution institution = Institution.create("C의원", InstitutionType.REQUESTER, ADDRESS, "11100004");
        UUID adminId = UUID.randomUUID();

        institution.delete(adminId);

        assertThat(institution.isActive()).isFalse();
        assertThat(institution.getDeletedAt()).isNotNull();
        assertThat(institution.getDeletedBy()).isEqualTo(adminId);
    }
}
