package healoworld.institution_service.application.service.partnership;

import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartnershipService {
    private final PartnershipRepository partnershipRepository;

    public boolean isActivePartnership(UUID requesterInstitutionId, UUID examInstitutionId) {
        return partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus(
                requesterInstitutionId, examInstitutionId, PartnershipStatus.ACTIVE);
    }

    public List<UUID> getPartnerExamInstitutionIds(UUID requesterInstitutionId) {
        return partnershipRepository.findExamInstitutionIds(requesterInstitutionId, PartnershipStatus.ACTIVE);
    }
}
