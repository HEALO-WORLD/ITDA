package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ReferralService {
    private final ReferralRepository referralRepository;

    public ReferralResponse createReferral(UUID userId, CreateReferralRequest createReferralRequest) {
        Optional<Referral> referral = referralRepository.findByIdempotencyKey(createReferralRequest.getIdempotencyKey());
        if (referral.isPresent()) {
            return ReferralResponse.from(referral.get());
        }
        Referral createdReferral = Referral.create(createReferralRequest.getReferringInstitutionId(), userId, createReferralRequest.getExamTypeId(), createReferralRequest.getPatientName(), createReferralRequest.getIdempotencyKey());
        referralRepository.save(createdReferral);

        // Reservation 생성
        return ReferralResponse.from(createdReferral);
    }

    @Transactional(readOnly = true)
    public ReferralResponse getReferral(UUID userId, UUID referralId, RoleType role) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        if (!(userId.equals(referral.getReferrerId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.REFERRAL_NOT_FOUND);
        }
        return ReferralResponse.from(referral);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReferralResponse> getReferrals(UUID userId, ReferralSearchCondition cond, RoleType role) {
        Page<ReferralResponse> page = referralRepository.search(cond, userId, role, cond.toPageable())
                .map(ReferralResponse::from);
        return PagedResponse.from(page);
    }

    public void deleteReferral(UUID userId, UUID referralId, RoleType role) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        if (!(userId.equals(referral.getReferrerId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.REFERRAL_NOT_FOUND);
        }
        referral.delete(userId);
        referralRepository.save(referral);
    }
}
