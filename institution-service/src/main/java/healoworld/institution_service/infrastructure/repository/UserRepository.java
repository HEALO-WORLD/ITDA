package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByLoginId(String loginId);

}
