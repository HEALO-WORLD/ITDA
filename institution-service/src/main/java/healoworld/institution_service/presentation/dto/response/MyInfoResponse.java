package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;

import java.util.UUID;

public record MyInfoResponse(UUID userId, String loginId, Role role,
                             UUID institutionId, String institutionName, InstitutionType institutionType) {
    public static MyInfoResponse from(User u) {
        Institution i = u.getInstitution();
        return new MyInfoResponse(u.getId(), u.getLoginId(), u.getRole(), i.getId(), i.getName(), i.getType());
    }
}
