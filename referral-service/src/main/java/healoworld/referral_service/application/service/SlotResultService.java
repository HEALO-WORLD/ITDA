package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.inbox.ConsumedEvent;
import healoworld.referral_service.global.inbox.ConsumedEventId;
import healoworld.referral_service.global.inbox.ConsumedEventRepository;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SlotResultService {
    private final ReferralRepository referralRepository;
    private final ReservationRepository reservationRepository;
    private final ReferralHistoryRepository referralHistoryRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final SlotCommandOutbox slotCommandOutbox;
    private final ExaminationService examinationService;

    public static final String CONSUMER = "referral-service";

    public void handle(UUID eventId, String eventType, UUID reservationId) {
        UUID referralId = reservationRepository.findReferralIdById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        Referral referral = referralRepository.findByIdForUpdate(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        if (consumedEventRepository.existsById(new ConsumedEventId(eventId, CONSUMER))) {
            log.info("이미 처리한 이벤트, 건너뜀. eventId={}, eventType={}", eventId, eventType);
            return;
        }

        switch (eventType) {
            case "SlotHeld" -> {
                if (isExpected(reservation, eventId, eventType, ReservationStatus.REQUESTED)) {
                    onSlotHeld(reservation, eventId);
                }
            }
            case "SlotHoldFailed" -> {
                if (isExpected(reservation, eventId, eventType, ReservationStatus.REQUESTED)) {
                    onSlotHoldFailed(referral, reservation, eventId);
                }
            }
            case "SlotBooked" -> {
                if (isExpected(reservation, eventId, eventType, ReservationStatus.HELD)) {
                    onSlotBooked(referral, reservation, eventId);
                } else if (isExpected(reservation, eventId, eventType, ReservationStatus.BLOCKED, ReservationStatus.FAILED, ReservationStatus.CANCELED)) {
                    releaseOrphanSlot(reservation, eventId);
                }
            }
            case "SlotBookFailed" -> {
                if (isExpected(reservation, eventId, eventType, ReservationStatus.HELD)) {
                    onSlotBookFailed(referral, reservation, eventId);
                }
            }
            case "HoldExpired" -> {
                if (isExpected(reservation, eventId, eventType, ReservationStatus.HELD)) {
                    onHoldExpired(referral, reservation, eventId);
                }
            }
            case "SlotsBlocked" -> {
                if (isExpected(reservation, eventId, eventType, ReservationStatus.REQUESTED, ReservationStatus.HELD, ReservationStatus.CONFIRMED)) {
                    onSlotsBlocked(referral, reservation, eventId);
                }
            }
            default -> log.warn("slot-results 알 수 없는 eventType, 처리 생략. eventId={}, eventType={}", eventId, eventType);
        }
        consumedEventRepository.save(ConsumedEvent.create(eventId, CONSUMER, eventType, LocalDateTime.now()));
    }

    // 예약 REQUESTED → HELD, BookSlot 발행
    private void onSlotHeld(Reservation reservation, UUID eventId) {
        LocalDateTime now = LocalDateTime.now();
        if (reservationRepository.holdIfRequested(reservation.getId(), now) == 0) {
            log.info("이미 처리된 SlotHeld 무시. eventId={}, reservationId={}", eventId, reservation.getId());
            return;
        }
        slotCommandOutbox.recordOutbox(reservation, "BookSlot");
    }

    // 예약 REQUESTED → FAILED(SLOT_CONFLICT), 의뢰 FAILED 또는 NEEDS_RESCHEDULE
    private void onSlotHoldFailed(Referral referral, Reservation reservation, UUID eventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();
        LocalDateTime now = LocalDateTime.now();

        if (reservationRepository.failIf(reservation.getId(), now, FailureReason.SLOT_CONFLICT, ReservationStatus.REQUESTED) == 0) {
            log.info("이미 처리된 SlotHoldFailed 무시. eventId={}, reservationId={}", eventId, reservation.getId());
            return;
        }

        ReferralStatus toStatus = failOrRequestReschedule(referral, reservation, now);

        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, toStatus, Reason.RESERVATION_FAILED, eventId, null);
        referralHistoryRepository.save(history);
    }

    // 예약 HELD → CONFIRMED, 의뢰 PENDING → CONFIRMED, 검사 생성
    private void onSlotBooked(Referral referral, Reservation reservation, UUID eventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();
        LocalDateTime now = LocalDateTime.now();

        if (reservationRepository.confirmIfHeld(reservation.getId(), now) == 0) {
            log.info("이미 처리된 SlotBooked 무시. eventId={}, reservationId={}", eventId, reservation.getId());
            return;
        }
        if (referralRepository.confirmIfPending(referral.getId(), now) == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);   // 예약 변경까지 롤백
        }

        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, ReferralStatus.CONFIRMED, Reason.RESERVATION_CONFIRMED, eventId, null);
        referralHistoryRepository.save(history);

        examinationService.syncScheduledExamination(
                referral.getId(),
                reservation.getId(),
                reservation.getExamInstitutionId()
        );
    }

    // 예약 HELD → FAILED(CONFIRM_FAILED), 의뢰 FAILED 또는 NEEDS_RESCHEDULE, ReleaseHold 발행
    private void onSlotBookFailed(Referral referral, Reservation reservation, UUID eventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();
        LocalDateTime now = LocalDateTime.now();

        if (reservationRepository.failIf(reservation.getId(), now, FailureReason.CONFIRM_FAILED, ReservationStatus.HELD) == 0) {
            log.info("이미 처리된 SlotBookFailed 무시. eventId={}, reservationId={}", eventId, reservation.getId());
            return;
        }

        ReferralStatus toStatus = failOrRequestReschedule(referral, reservation, now);

        UUID outboxEventId = slotCommandOutbox.recordOutbox(reservation, "ReleaseHold");

        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, toStatus, Reason.RESERVATION_FAILED, outboxEventId, null);
        referralHistoryRepository.save(history);
    }

    // 예약 HELD → FAILED(HOLD_EXPIRED), 의뢰 FAILED 또는 NEEDS_RESCHEDULE, ReleaseHold 발행
    private void onHoldExpired(Referral referral, Reservation reservation, UUID eventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();
        LocalDateTime now = LocalDateTime.now();

        if (reservationRepository.failIf(reservation.getId(), now, FailureReason.HOLD_EXPIRED, ReservationStatus.HELD) == 0) {
            log.info("이미 처리된 HoldExpired 무시. eventId={}, reservationId={}", eventId, reservation.getId());
            return;
        }

        ReferralStatus toStatus = failOrRequestReschedule(referral, reservation, now);

        UUID outboxEventId = slotCommandOutbox.recordOutbox(reservation, "ReleaseHold");

        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, toStatus, Reason.RESERVATION_FAILED, outboxEventId, null);
        referralHistoryRepository.save(history);
    }

    // 예약 진행 중 → BLOCKED(EQUIPMENT_FAILURE), 의뢰 PENDING·CONFIRMED → NEEDS_RESCHEDULE
    private void onSlotsBlocked(Referral referral, Reservation reservation, UUID eventId) {
        ReferralStatus fromStatus = referral.getReferralStatus();
        LocalDateTime now = LocalDateTime.now();

        if (reservationRepository.blockIfActive(reservation.getId(), now) == 0) {
            log.info("이미 처리된 SlotsBlocked 무시. eventId={}, reservationId={}", eventId, reservation.getId());
            return;
        }
        if (referralRepository.requestRescheduleIfIn(referral.getId(), now, List.of(ReferralStatus.PENDING, ReferralStatus.CONFIRMED)) == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);   // 예약 변경까지 롤백
        }

        ReferralHistory history = ReferralHistory.create(referral, reservation, fromStatus, ReferralStatus.NEEDS_RESCHEDULE, Reason.RESERVATION_BLOCKED, eventId, null);
        referralHistoryRepository.save(history);
    }

    private boolean isExpected(Reservation reservation, UUID eventId, String eventType, ReservationStatus... expected) {
        if (Arrays.asList(expected).contains(reservation.getReservationStatus())) return true;
        log.info("기대하지 않은 상태의 이벤트 무시. eventType={}, eventId={}, reservationId={}, status={}", eventType, eventId, reservation.getId(), reservation.getReservationStatus());
        return false;
    }

    // 의뢰 PENDING → 재예약이면 NEEDS_RESCHEDULE, 첫 예약이면 FAILED. 바뀐 상태를 돌려준다
    private ReferralStatus failOrRequestReschedule(Referral referral, Reservation reservation, LocalDateTime now) {
        boolean isRetry = reservation.getAttemptNo() > 1;
        int updated = isRetry
                ? referralRepository.requestRescheduleIfIn(referral.getId(), now, List.of(ReferralStatus.PENDING))
                : referralRepository.failIfPending(referral.getId(), now);
        if (updated == 0) {
            throw new BaseException(ErrorCode.INVALID_REFERRAL_STATUS);   // 예약 변경까지 롤백
        }
        return isRetry ? ReferralStatus.NEEDS_RESCHEDULE : ReferralStatus.FAILED;
    }

    // 이미 끝난 예약(BLOCKED/FAILED/CANCELED)에 슬롯이 확정되면 슬롯을 풀어준다
    private void releaseOrphanSlot(Reservation reservation, UUID eventId) {
        UUID releaseEventId = slotCommandOutbox.recordOutbox(reservation, "ReleaseSlot");
        log.warn("종료된 예약에 SlotBooked 수신, 보상 ReleaseSlot 발행. eventId={}, reservationId={}, status={}, releaseEventId={}",
                eventId, reservation.getId(), reservation.getReservationStatus(), releaseEventId);
    }
}
