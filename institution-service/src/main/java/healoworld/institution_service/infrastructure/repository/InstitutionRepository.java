package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstitutionRepository extends JpaRepository<Institution, UUID> {
    // 회원가입: 삭제되지 않은 기관만
    Optional<Institution> findByIdAndDeletedAtIsNull(UUID id);

    // 가입용 기관 목록: 활성 + 삭제 안 됨 + 운영사 제외
    List<Institution> findAllByActiveTrueAndDeletedAtIsNullAndTypeNot(InstitutionType type);

}
