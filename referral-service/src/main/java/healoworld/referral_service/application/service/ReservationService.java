package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.command.SlotCommandType;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ReservationService {
    private final ReservationRepository reservationRepository;
    private final ReferralHistoryRepository referralHistoryRepository;
    private final SlotCommandOutbox slotCommandOutbox;
    private final ReferralRepository referralRepository;

    public Reservation createReservation(UUID outboxEventId, Referral referral, CreateReservationRequest createReservationRequest) {
        int attemptNo = reservationRepository.findTopByReferralIdOrderByAttemptNoDesc(referral.getId())
                .map(reservation -> reservation.getAttemptNo() + 1)
                .orElse(1);

        Reservation createdReservation = Reservation.create(referral, attemptNo, createReservationRequest.getSlotId(), createReservationRequest.getExamInstitutionId(), createReservationRequest.getEquipmentId(), createReservationRequest.getExamDate(), createReservationRequest.getStartTime(), createReservationRequest.getEndTime());
        reservationRepository.save(createdReservation);

        slotCommandOutbox.recordOutbox(createdReservation, outboxEventId, SlotCommandType.HOLD_REQUESTED);

        return createdReservation;
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID institutionId, UUID reservationId, RoleType role) {
        Reservation reservation = reservationRepository.findByIdWithReferral(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!(institutionId.equals(reservation.getReferral().getReferringInstitutionId())
                || role == RoleType.ADMIN
                || institutionId.equals(reservation.getExamInstitutionId()))
        ) {
            throw new BaseException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        return ReservationResponse.from(reservation);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ReservationResponse> getReservations(UUID institutionId, ReservationSearchCondition cond, RoleType role) {
        Page<ReservationResponse> page = reservationRepository.search(cond, institutionId, role, cond.toPageable())
                .map(ReservationResponse::from);
        return PagedResponse.from(page);
    }

    public void deleteReservation(UUID userId, UUID reservationId, RoleType role) {
        Reservation reservation = reservationRepository.findByIdWithReferral(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!(userId.equals(reservation.getReferral().getReferrerId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        if (reservationRepository.softDeleteIf(reservationId, userId, LocalDateTime.now(), List.of(ReservationStatus.FAILED, ReservationStatus.CANCELED, ReservationStatus.BLOCKED)) == 0){
            throw new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    // 예약 취소
    public void cancelReservation(UUID userId, UUID institutionId, RoleType role, UUID reservationId) {
        UUID referralId = reservationRepository.findReferralIdById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        Referral referral = referralRepository.findByIdForUpdate(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!(institutionId.equals(referral.getReferringInstitutionId())
                || role == RoleType.ADMIN)
        ) {
            throw new BaseException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now();

        if (!now.isBefore(reservation.getStartTime().minusHours(2))) {
            throw new BaseException(ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
        }

        ReferralStatus fromStatus = referral.getReferralStatus();

        if (reservationRepository.cancelIfConfirmed(reservation.getId(), userId, now) == 0) {
            throw new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
        }
        if (referralRepository.cancelIfCancelable(referral.getId(), userId, now) == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);
        }
        UUID eventId = slotCommandOutbox.recordOutbox(reservation, SlotCommandType.RELEASE_SLOT);

        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, ReferralStatus.CANCELED, Reason.CANCELED_BY_REQUESTER, eventId, userId);
        referralHistoryRepository.save(history);
    }
}
