package healoworld.institution_service.application.service.auth;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Getter
@RequiredArgsConstructor
public enum DevTestAccount {
    REQUESTER_A(
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            InstitutionType.REQUESTER),

    REQUESTER_D(
            UUID.fromString("10000000-0000-0000-0000-000000000002"),
            UUID.fromString("20000000-0000-0000-0000-000000000002"),
            InstitutionType.REQUESTER),

    EXAMINER_B(
            UUID.fromString("10000000-0000-0000-0000-000000000003"),
            UUID.fromString("20000000-0000-0000-0000-000000000003"),
            InstitutionType.EXAM),

    ADMIN(
            UUID.fromString("10000000-0000-0000-0000-000000000009"),
            UUID.fromString("20000000-0000-0000-0000-000000000009"),
            InstitutionType.PLATFORM);

    private final UUID userId;
    private final UUID institutionId;
    private final InstitutionType institutionType;

    public Role getRole() {
        return institutionType.getMemberRole();
    }

}
