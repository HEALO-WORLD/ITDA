package healoworld.referral_service.application.service;

import feign.FeignException;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.client.institution.EquipmentOwnerResponse;
import healoworld.referral_service.infrastructure.client.institution.InstitutionClient;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@RequiredArgsConstructor
public class ReferralService {
    private final ReferralRepository referralRepository;
    private final ReferralHistoryService referralHistoryService;
    private final ReservationService reservationService;
    private final ReservationRepository reservationRepository;
    private final InstitutionClient institutionClient;
    private final ExaminationRepository examinationRepository;

    public ReferralResponse createReferral(UUID userId, UUID institutionId, CreateReferralRequest createReferralRequest) {
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

        if (!institutionId.equals(createReferralRequest.getReferringInstitutionId())) {
            throw new BaseException(ErrorCode.REFERRAL_FORBIDDEN);
        }

        if (!isActivePartnership(createReferralRequest.getReferringInstitutionId(), createReferralRequest.getReservation().getExamInstitutionId())
        ) {
            throw new BaseException(ErrorCode.PARTNERSHIP_NOT_ACTIVE);
        }

        validateEquipment(createReferralRequest.getReservation().getEquipmentId(), createReferralRequest.getReservation().getExamInstitutionId());

        validateSlot();

        Referral createdReferral = Referral.create(createReferralRequest.getReferringInstitutionId(), userId, createReferralRequest.getExamTypeId(), createReferralRequest.getPatientName(), createReferralRequest.getIdempotencyKey());
        try {
            referralRepository.saveAndFlush(createdReferral);
        } catch (DataIntegrityViolationException e) {
            throw new BaseException(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
        }

        UUID outboxEventId = UUID.randomUUID();

        Reservation reservation = reservationService.createReservation(outboxEventId, createdReferral, createReferralRequest.getReservation());
        createdReferral.assignReservation(reservation.getId());

        referralHistoryService.recordHistory(createdReferral, reservation, null, createdReferral.getReferralStatus(), Reason.REFERRAL_CREATED, outboxEventId, userId);

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

        // 검사가 이미 시작·완료됐다면 새 예약에 검사를 다시 연결할 수 없으므로, 새 예약·Outbox를 만들기 전에 거절한다
        // (검사가 없거나 SCHEDULED이면 기존처럼 재예약을 받는다)
        examinationRepository.findByReferralId(referralId)
                .filter(examination -> examination.getStatus() != ExaminationStatus.SCHEDULED)
                .ifPresent(examination -> {
                    throw new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
                });

        if (!isActivePartnership(referral.getReferringInstitutionId(), request.getExamInstitutionId())
        ) {
            throw new BaseException(ErrorCode.PARTNERSHIP_NOT_ACTIVE);
        }

        validateEquipment(request.getEquipmentId(), request.getExamInstitutionId());

        UUID outboxEventId = UUID.randomUUID();
        Reservation reservation = reservationService.createReservation(outboxEventId, referral, request);

        if (referralRepository.rescheduleIfNeedsReschedule(referralId, reservation.getId(), userId, LocalDateTime.now()) == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);
        }
        // referralHistory 기록
        referralHistoryService.recordHistory(referral, reservation, ReferralStatus.NEEDS_RESCHEDULE, ReferralStatus.PENDING, Reason.RESCHEDULED, outboxEventId, userId);

        return ReservationResponse.from(reservation);
    }

    private boolean isActivePartnership(UUID requesterId, UUID examInstitutionId) {
        try {
            return institutionClient.check(requesterId, examInstitutionId).active();
        } catch (FeignException e) {
            log.warn("협력 관계 확인 실패. requester={}, exam={}", requesterId, examInstitutionId, e);
            throw new BaseException(ErrorCode.INSTITUTION_SERVICE_UNAVAILABLE);
        }
    }

    private void validateEquipment(UUID equipmentId, UUID examInstitutionId) {
        try {
            ApiResponse<EquipmentOwnerResponse> response = institutionClient.getEquipmentOwner(equipmentId);
            if (!examInstitutionId.equals(response.getData().institutionId())) {
                throw new BaseException(ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
            }
        } catch (FeignException.NotFound e) {
            log.info("장비 없음. equipment={}, exam={}", equipmentId, examInstitutionId);
            throw new BaseException(ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
        } catch (FeignException e) {
            log.warn("장비 소유 확인 실패. equipment={}, exam={}", equipmentId, examInstitutionId, e);
            throw new BaseException(ErrorCode.INSTITUTION_SERVICE_UNAVAILABLE);
        }
    }

    private void validateSlot() {

    }
}
