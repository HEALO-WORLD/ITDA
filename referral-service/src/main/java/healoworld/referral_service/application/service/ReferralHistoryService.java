package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.response.ReferralHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ReferralHistoryService {
    private final ReferralHistoryRepository referralHistoryRepository;
    private final ReferralRepository referralRepository;

    @Transactional(readOnly = true)
    public List<ReferralHistoryResponse> getReferralHistories(UUID institutionId, UUID referralId, RoleType role) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        if (!(institutionId.equals(referral.getReferringInstitutionId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.REFERRAL_NOT_FOUND);
        }

        return referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referralId)
                .stream()
                .map(ReferralHistoryResponse::from).toList();
    }
}
