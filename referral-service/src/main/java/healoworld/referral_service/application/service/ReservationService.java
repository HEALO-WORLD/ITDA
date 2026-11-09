package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.command.SlotCommandType;
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
        // 삭제된 예약의 번호도 다시 쓰지 않도록 소프트 삭제된 예약까지 포함한 마지막 번호 + 1 (예약이 없으면 1)
        int attemptNo = reservationRepository.findMaxAttemptNoIncludingDeleted(referral.getId()) + 1;

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

    // 예약 삭제는 제공하지 않는다 (MVP 정책: 예약 이력 보존, Kafka 비동기 결과 처리와의 정합성)
    // 예약을 끝내려면 취소(cancelReservation)를 사용한다. 이미 소프트 삭제된 과거 데이터는 그대로 둔다

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
