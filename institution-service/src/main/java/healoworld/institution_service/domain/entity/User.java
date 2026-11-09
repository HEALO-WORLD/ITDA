package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.entity.BaseEntity;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "p_users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String loginId;

    @Column(nullable = false, length = 100)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(nullable = false)
    private boolean active;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "institution_id", nullable = false)
    private Institution institution;

    // 회원가입: 역할은 소속기관 유형으로 자동 결정, 승인 전까지 비활성
    public static User signup(String loginId, String encodedPassword, Institution institution) {
        User user = new User();
        user.loginId = loginId;
        user.password = encodedPassword;
        user.institution = institution;
        user.role = institution.getType().getMemberRole();
        user.active = false;
        return user;
    }

    public void approve() {
        if (this.active) {
            throw new BaseException(ErrorCode.USER_ALREADY_APPROVED);
        }
        this.active = true;
    }
}
