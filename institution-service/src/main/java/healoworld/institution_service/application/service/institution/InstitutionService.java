package healoworld.institution_service.application.service.institution;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.presentation.dto.response.PublicInstitutionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstitutionService {
    private final InstitutionRepository institutionRepository;

    public List<PublicInstitutionResponse> getPublicInstitutions() {
        return institutionRepository.findAllByActiveTrueAndDeletedAtIsNullAndTypeNot(InstitutionType.PLATFORM)
                .stream().map(PublicInstitutionResponse::from)
                .toList();
    }
}
