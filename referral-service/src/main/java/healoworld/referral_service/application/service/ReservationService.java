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

        String eventType = "HoldRequested";
        slotCommandOutbox.recordOutbox(createdReservation, outboxEventId, eventType);

        return createdReservation;
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID institutionId, UUID reservationId, RoleType role) {
        Reservation reservation = reservationRepository.findById(reservationId)
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
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!(userId.equals(reservation.getReferral().getReferrerId()) || role == RoleType.ADMIN)) {
            throw new BaseException(ErrorCode.RESERVATION_NOT_FOUND);
        }
        reservation.delete(userId);
        reservationRepository.save(reservation);
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

        // 시간 체크 2시간 전
        if (!LocalDateTime.now().isBefore(reservation.getStartTime().minusHours(2))) {
            throw new BaseException(ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
        }

        ReferralStatus fromStatus = referral.getReferralStatus();

        // reservation status CANCELED 변경
        reservation.cancel();
        reservationRepository.save(reservation);

        // referral status CANCELED 변경
        referral.cancel();

        // ReleaseSlot 이벤트 발행 (Outbox 기록)
        String eventType = "ReleaseSlot";
        UUID eventId = slotCommandOutbox.recordOutbox(reservation, eventType);

        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, referral.getReferralStatus(), Reason.CANCELED_BY_REQUESTER, eventId, userId);
        referralHistoryRepository.save(history);
    }
}
