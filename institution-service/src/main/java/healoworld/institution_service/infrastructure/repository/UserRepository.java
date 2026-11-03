package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    boolean existsByLoginId(String loginId);

    Optional<User> findByLoginIdAndDeletedAtIsNull(String loginId);

    @EntityGraph(attributePaths = "institution")
    List<User> findAllByActiveAndDeletedAtIsNullOrderByCreatedAtDesc(boolean active);

    @EntityGraph(attributePaths = "institution")
    List<User> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = "institution")
    Optional<User> findWithInstitutionByIdAndDeletedAtIsNull(UUID id);
}
