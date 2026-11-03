package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.domain.enums.Role;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserSummaryResponse(UUID userId, String loginId, Role role, boolean active,
                                  UUID institutionId, String institutionName, LocalDateTime createdAt) {
    public static UserSummaryResponse from(User u) {
        return new UserSummaryResponse(u.getId(), u.getLoginId(), u.getRole(), u.isActive(),
                u.getInstitution().getId(), u.getInstitution().getName(), u.getCreatedAt());
    }
}