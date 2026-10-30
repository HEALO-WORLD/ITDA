package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class ReservationService {
    private final ReservationRepository reservationRepository;

    public Reservation createReservation(Referral referral, CreateReservationRequest createReservationRequest) {
        int attemptNo = reservationRepository.findTopByReferralIdOrderByAttemptNoDesc(referral.getId())
                .map(reservation -> reservation.getAttemptNo() + 1)
                .orElse(1);

        Reservation createdReservation = Reservation.create(referral, attemptNo, createReservationRequest.getSlotId(), createReservationRequest.getExamInstitutionId(), createReservationRequest.getEquipmentId(), createReservationRequest.getExamDate(), createReservationRequest.getStartTime(), createReservationRequest.getEndTime());
        reservationRepository.save(createdReservation);

        // HoldRequested 이벤트 발행 (Outbox 기록)
        // referralHistory 기록
        return createdReservation;
    }

    // SlotHeld 이벤트 수신
    public void bookSlot() {
        // reservation status HELD 변경
        // BookSlot 이벤트 발행 (Outbox 기록)
    }

    // SlotHoldFailed 이벤트 수신
    public void slotHoldFailed() {
        // reservation status FAILED 변경
        // failureReason SLOT_CONFLICT 변경
        // referral status FAILED or NEEDS_RESCHEDULE 변경
        // referralHistory 기록
    }

    // SlotBooked 이벤트 수신
    public void slotBooked() {
        // reservation status CONFIRMED 변경
        // reservation confirmedAt 기록
        // referral status CONFIRMED 변경
        // referralHistory 기록
        // 검사 생성
    }

    // SlotBookFailed 이벤트 수신
    public void slotBookFailed() {
        // ReleaseHold 이벤트 발행
        // reservation status FAILED 변경
        // failureReason CONFIRM_FAILED 변경
        // referral status FAILED or NEEDS_RESCHEDULE 변경
        // referralHistory 기록
    }

    // HoldExpired 이벤트 수신
    public void holdExpired() {
        // ReleaseHold 이벤트 발행
        // reservation status FAILED 변경
        // failureReason HOLD_EXPIRED 변경
        // referral status FAILED or NEEDS_RESCHEDULE 변경
        // referralHistory 기록
    }

    // SlotsBlocked 이벤트 수신
    public void slotsBlocked() {
        // reservation status BLOCKED 변경
        // failureReason EQUIPMENT_FAILURE 변경
        // referral status NEEDS_RESCHEDULE 변경
        // referralHistory 기록
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
    public void cancelReservation(UUID userId, UUID reservationId, RoleType role) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        // 시간 체크 2시간 전
        reservation.cancel();
        reservationRepository.save(reservation);
        // ReleaseSlot 이벤트 발행 (Outbox 기록)
        // reservation status CANCELED 변경
        // failureReason CANCELED_BY_REQUESTER 변경
        // referral status CANCELED 변경
        // referralHistory 기록
    }
}
