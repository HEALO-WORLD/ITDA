package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.global.entity.BaseEntity;
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
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstitutionType type;

    private String address;

    @Column(nullable = false)
    private boolean active;

    // 운영사 기관은 회원가입 대상이 아님 (가입하면 자동으로 admin)
    public boolean isSignupAllowed() {
        return type != InstitutionType.PLATFORM;
    }
}
