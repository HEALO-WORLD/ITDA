package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.global.entity.BaseEntity;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "p_institutions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Institution extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 100;
    private static final int ADDRESS_MAX_LENGTH = 255;
    private static final String CARE_INSTITUTION_CODE_REGEX = "\\d{8}";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstitutionType type;

    @Column(length = ADDRESS_MAX_LENGTH)
    private String address;

    @Column(nullable = false)
    private boolean active;

    @Column(unique = true, length = 8)
    private String careInstitutionCode;   // 요양기관기호, PLATFORM은 null

    public static Institution create(String name, InstitutionType type, String address, String careInstitutionCode) {
        validateName(name);
        validateAddress(address);
        validateType(type);
        validateCareInstitutionCode(type, careInstitutionCode);

        Institution institution = new Institution();
        institution.name = name;
        institution.type = type;
        institution.address = address;
        institution.careInstitutionCode = careInstitutionCode;
        institution.active = true;   // 관리자 등록 기관은 바로 활성
        return institution;
    }

    public void update(String name, String address) {
        validateName(name);
        validateAddress(address);
        this.name = name;
        this.address = address;
    }

    public void delete(UUID deletedBy) {
        this.active = false;
        softDelete(deletedBy);
    }

    // 운영사 기관은 회원가입 대상이 아님 (가입하면 자동으로 admin)
    public boolean isSignupAllowed() {
        return type != InstitutionType.PLATFORM;
    }

    // 불변식 검증
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > NAME_MAX_LENGTH) {
            throw new BaseException(ErrorCode.INVALID_INSTITUTION_NAME);
        }
    }

    private static void validateAddress(String address) {
        if (address == null || address.isBlank() || address.length() > ADDRESS_MAX_LENGTH) {
            throw new BaseException(ErrorCode.INVALID_INSTITUTION_ADDRESS);
        }
    }

    private static void validateType(InstitutionType type) {
        if (type == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
    }

    // 의료기관 → 숫자 8자리 필수 / 운영사 → 없어야 함
    private static void validateCareInstitutionCode(InstitutionType type, String code) {
        boolean valid = type.isMedical()
                ? code != null && code.matches(CARE_INSTITUTION_CODE_REGEX)
                : code == null;
        if (!valid) {
            throw new BaseException(ErrorCode.INVALID_CARE_INSTITUTION_CODE);
        }
    }
}
