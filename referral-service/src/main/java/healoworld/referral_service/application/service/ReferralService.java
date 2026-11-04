package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ReferralService {
    private final ReferralRepository referralRepository;
    private final ReferralHistoryRepository referralHistoryRepository;
    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;

    public ReferralResponse createReferral(UUID userId, CreateReferralRequest createReferralRequest) {
        Optional<Referral> referral = referralRepository.findByIdempotencyKey(createReferralRequest.getIdempotencyKey());
        if (referral.isPresent()) {
            Optional<Reservation> firstReservation = reservationRepository.findByReferralIdAndAttemptNo(referral.get().getId(), 1);
            if (firstReservation.isPresent()) {
                if (!referral.get().isSameRequest(userId, createReferralRequest.getReferringInstitutionId(), createReferralRequest.getExamTypeId(), createReferralRequest.getPatientName())
                        || !createReferralRequest.getReservation().getSlotId().equals(firstReservation.get().getSlotId())
                ) {
                    throw new BaseException(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
                }
                return ReferralResponse.from(referral.get());
            }
            throw new BaseException(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
        }

        Referral createdReferral = Referral.create(createReferralRequest.getReferringInstitutionId(), userId, createReferralRequest.getExamTypeId(), createReferralRequest.getPatientName(), createReferralRequest.getIdempotencyKey());
        try {
            referralRepository.saveAndFlush(createdReferral);
        } catch (DataIntegrityViolationException e) {
            throw new BaseException(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
        }

        UUID outboxEventId = UUID.randomUUID();

        Reservation reservation = reservationService.createReservation(outboxEventId, createdReferral, createReferralRequest.getReservation());
        createdReferral.assignReservation(reservation.getId());

        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(createdReferral, reservation, null, createdReferral.getReferralStatus(), Reason.REFERRAL_CREATED, outboxEventId, userId);
        referralHistoryRepository.save(history);

        return ReferralResponse.from(createdReferral);
    }

    @Transactional(readOnly = true)
    public ReferralResponse getReferral(UUID institutionId, UUID referralId, RoleType role) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        if (!(institutionId.equals(referral.getReferringInstitutionId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.REFERRAL_NOT_FOUND);
        }
        return ReferralResponse.from(referral);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReferralResponse> getReferrals(UUID institutionId, ReferralSearchCondition cond, RoleType role) {
        Page<ReferralResponse> page = referralRepository.search(cond, institutionId, role, cond.toPageable())
                .map(ReferralResponse::from);
        return PagedResponse.from(page);
    }

    public void deleteReferral(UUID userId, UUID referralId, RoleType role) {
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        if (!(userId.equals(referral.getReferrerId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.REFERRAL_NOT_FOUND);
        }

        if (referralRepository.softDeleteIf(referralId, userId, LocalDateTime.now(), List.of(ReferralStatus.FAILED, ReferralStatus.CANCELED)) == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);
        }
    }

    public ReservationResponse createRescheduledReservation(UUID userId, RoleType role, UUID referralId, CreateReservationRequest request) {
        Referral referral = referralRepository.findByIdForUpdate(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        if (!(userId.equals(referral.getReferrerId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.REFERRAL_NOT_FOUND);
        }

        referral.validateReschedulable();
        UUID outboxEventId = UUID.randomUUID();
        Reservation reservation = reservationService.createReservation(outboxEventId, referral, request);

        if (referralRepository.rescheduleIfNeedsReschedule(referralId, reservation.getId(), userId, LocalDateTime.now()) == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);
        }
        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, ReferralStatus.NEEDS_RESCHEDULE, ReferralStatus.PENDING, Reason.RESCHEDULED, outboxEventId, userId);
        referralHistoryRepository.save(history);
        return ReservationResponse.from(reservation);
    }
}
