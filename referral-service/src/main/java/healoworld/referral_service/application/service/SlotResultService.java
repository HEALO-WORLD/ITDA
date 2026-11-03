package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SlotResultService {
    private final ReservationRepository reservationRepository;
    private final ReferralRepository referralRepository;
    private final ReferralHistoryRepository referralHistoryRepository;
    private final SlotCommandOutbox slotCommandOutbox;

    public void handle(UUID eventId, String eventType, UUID reservationId, UUID referralId) {

        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        switch (eventType) {
            case "SlotHeld":
                bookSlotHandler(referral, reservation);
                break;

            case "SlotHoldFailed":
                slotHoldFailedHandler(referral, reservation, eventId);
                break;

            case "SlotBooked":
                slotBookedHandler(referral, reservation, eventId);
                break;

            case "SlotBookFailed":
                slotBookFailedHandler(referral, reservation);
                break;

            case "SlotsBlocked":
                slotsBlockedHandler(referral, reservation, eventId);
                break;

            case "HoldExpired":
                holdExpiredHandler(referral, reservation);
                break;

            default:
                log.warn("slot-results 알 수 없는 eventType, 처리 생략. eventId={}, eventType={}", eventId, eventType);
                return;
        }
        log.info("slot-results 핸들러 완료(커밋 전). eventId={}, eventType={}, reservationId={}, reservationStatus={}, referralStatus={}",
                eventId, eventType, reservation.getId(), reservation.getReservationStatus(), referral.getReferralStatus());
    }

    // SlotHeld 이벤트 수신
    private void bookSlotHandler(Referral referral, Reservation reservation) {
        // reservation status HELD 변경
        reservation.hold();

        // BookSlot 이벤트 발행 (Outbox 기록)
        String eventType = "BookSlot";
        slotCommandOutbox.recordOutbox(reservation, eventType);
    }

    // SlotHoldFailed 이벤트 수신
    private void slotHoldFailedHandler(Referral referral, Reservation reservation, UUID outboxEventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();

        // failureReason SLOT_CONFLICT or EQUIPMENT_FAILURE
        // reservation status FAILED 변경
        reservation.fail(FailureReason.SLOT_CONFLICT);

        // referral status FAILED
        referral.fail();
        // 전 상태가 needs_reschedule이면 needs_reschedule로
        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, referral.getReferralStatus(), Reason.RESERVATION_FAILED, outboxEventId, null);
        referralHistoryRepository.save(history);
    }

    // SlotBooked 이벤트 수신
    private void slotBookedHandler(Referral referral, Reservation reservation, UUID outboxEventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();

        // reservation status CONFIRMED 변경
        // reservation confirmedAt 기록
        reservation.confirm();

        // referral status CONFIRMED 변경
        referral.confirm();

        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, referral.getReferralStatus(), Reason.RESERVATION_CONFIRMED, outboxEventId, null);
        referralHistoryRepository.save(history);
        // 검사 생성
    }

    // SlotBookFailed 이벤트 수신
    private void slotBookFailedHandler(Referral referral, Reservation reservation) {
        ReferralStatus fromStatus = referral.getReferralStatus();

        // reservation status FAILED 변경
        // failureReason CONFIRM_FAILED 변경
        reservation.fail(FailureReason.CONFIRM_FAILED);

        // referral status FAILED or NEEDS_RESCHEDULE 변경
        referral.fail();

        // ReleaseHold 이벤트 발행 이벤트 발행 (Outbox 기록)
        String eventType = "ReleaseHold";
        UUID eventId = slotCommandOutbox.recordOutbox(reservation, eventType);

        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, referral.getReferralStatus(), Reason.RESERVATION_FAILED, eventId, null);
        referralHistoryRepository.save(history);
    }

    // HoldExpired 이벤트 수신
    private void holdExpiredHandler(Referral referral, Reservation reservation) {
        ReferralStatus fromStatus = referral.getReferralStatus();

        // reservation status FAILED 변경
        // failureReason HOLD_EXPIRED 변경
        reservation.fail(FailureReason.HOLD_EXPIRED);

        // referral status FAILED or NEEDS_RESCHEDULE 변경
        referral.fail();

        // ReleaseHold 이벤트 발행
        String eventType = "ReleaseHold";
        UUID eventId = slotCommandOutbox.recordOutbox(reservation, eventType);


        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, referral.getReferralStatus(), Reason.RESERVATION_FAILED, eventId, null);
        referralHistoryRepository.save(history);
    }

    // SlotsBlocked 이벤트 수신
    private void slotsBlockedHandler(Referral referral, Reservation reservation, UUID outboxEventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();
        // reservation status BLOCKED 변경
        // failureReason EQUIPMENT_FAILURE 변경
        reservation.block();

        // referral status NEEDS_RESCHEDULE 변경
        referral.requestReschedule();

        // referralHistory 기록
        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, referral.getReferralStatus(), Reason.RESERVATION_BLOCKED, outboxEventId, null);
        referralHistoryRepository.save(history);
    }
}
