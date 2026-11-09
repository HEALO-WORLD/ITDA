package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.domain.enums.Role;

import java.util.UUID;

public record SignupResponse(UUID userId, String loginId, Role role, boolean active) {

    public static SignupResponse from(User user) {
        return new SignupResponse(user.getId(), user.getLoginId(), user.getRole(), user.isActive());
    }
}
