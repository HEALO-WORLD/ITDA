package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReferralRepositoryCustom {
    Page<Referral> search(ReferralSearchCondition cond, UUID userId, RoleType role, Pageable pageable);
}
