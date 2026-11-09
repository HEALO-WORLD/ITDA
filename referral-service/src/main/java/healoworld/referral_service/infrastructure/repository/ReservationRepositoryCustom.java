package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface ReservationRepositoryCustom {
    Page<Reservation> search(ReservationSearchCondition cond, UUID userId, RoleType role, Pageable pageable);
}
