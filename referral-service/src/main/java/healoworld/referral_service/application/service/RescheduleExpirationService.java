package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 재조정 미처리 의뢰 자동 종료. 기존 예약은 BLOCKED/FAILED 상태 그대로 보존한다.
 *
 * <p>기한: max(최신 차단 예약의 검사 시작 시각, 차단 시각) + graceHours.
 * 의뢰 → 현재 예약 → 검사 순으로 잠그므로 재예약/검사 시작과 충돌하지 않는다.</p>
 */
@Service
public class RescheduleExpirationService {
    private final ReferralRepository referralRepository;
    private final IncidentReservationRepository incidentReservationRepository;
    private final ReservationRepository reservationRepository;
    private final ExaminationRepository examinationRepository;
    private final ExaminationResultRepository examinationResultRepository;
    private final ReferralHistoryService referralHistoryService;
    private final NotificationService notificationService;
    private final Clock clock;
    private final long graceHours;

    public RescheduleExpirationService(ReferralRepository referralRepository,
                                       IncidentReservationRepository incidentReservationRepository,
                                       ReservationRepository reservationRepository,
                                       ExaminationRepository examinationRepository,
                                       ExaminationResultRepository examinationResultRepository,
                                       ReferralHistoryService referralHistoryService,
                                       NotificationService notificationService,
                                       Clock clock,
                                       @Value("${itda.reschedule-expiration.scheduler.grace-hours:48}") long graceHours) {
        if (graceHours < 0) {
            throw new IllegalArgumentException("grace-hours는 0 이상이어야 합니다.");
        }
        this.referralRepository = referralRepository;
        this.incidentReservationRepository = incidentReservationRepository;
        this.reservationRepository = reservationRepository;
        this.examinationRepository = examinationRepository;
        this.examinationResultRepository = examinationResultRepository;
        this.referralHistoryService = referralHistoryService;
        this.notificationService = notificationService;
        this.clock = clock;
        this.graceHours = graceHours;
    }

    @Transactional
    public boolean expireIfOverdue(UUID referralId) {
        // 재예약도 같은 의뢰 행에 FOR UPDATE를 사용하므로 두 요청 중 먼저 잠근 쪽만 진행된다.
        Optional<Referral> found = referralRepository.findByIdForUpdate(referralId);
        if (found.isEmpty()) {
            return false;
        }
        Referral referral = found.get();
        if (referral.getReferralStatus() != ReferralStatus.NEEDS_RESCHEDULE
                || referral.getCurrentReservationId() == null) {
            return false;
        }

        // 검사 시작/장애 처리와 같은 순서: 의뢰 → 예약 → 검사.
        Optional<Reservation> current = incidentReservationRepository.findByIdForUpdate(referral.getCurrentReservationId());
        if (current.isEmpty()
                || (current.get().getReservationStatus() != ReservationStatus.BLOCKED
                    && current.get().getReservationStatus() != ReservationStatus.FAILED)) {
            return false;
        }

        // 재예약 실패 후에도 이전 BLOCKED 예약을 찾아 원래 만료 기한을 유지한다.
        Optional<Reservation> blocked = reservationRepository.findLatestBlocked(referralId);
        if (blocked.isEmpty() || blocked.get().getEndedAt() == null) {
            return false;
        }
        Reservation blockedReservation = blocked.get();
        LocalDateTime deadlineBase = blockedReservation.getStartTime().isAfter(blockedReservation.getEndedAt())
                ? blockedReservation.getStartTime() : blockedReservation.getEndedAt();
        LocalDateTime deadline = deadlineBase.plusHours(graceHours);
        if (LocalDateTime.now(clock).isBefore(deadline)) {
            return false;
        }

        Optional<Examination> examination = examinationRepository.findByReferralIdForUpdate(referralId);
        if (examination.isPresent()) {
            Examination exam = examination.get();
            // 과거 운영 데이터의 상태가 SCHEDULED여도 시작·완료 기록이나 결과가 있으면 종료하지 않는다.
            if (exam.getStatus() != ExaminationStatus.SCHEDULED
                    || exam.getStartedAt() != null
                    || exam.getCompletedAt() != null
                    || examinationResultRepository.existsByExaminationId(exam.getId())) {
                return false;
            }
        }

        // 상태·이력·알림이 한 트랜잭션에서 커밋된다. 잠금 이후 상태를 재확인했으므로 멱등이다.
        referral.expire();
        referralHistoryService.recordHistory(referral, blockedReservation,
                ReferralStatus.NEEDS_RESCHEDULE, ReferralStatus.EXPIRED,
                Reason.RESCHEDULE_TIMEOUT, null, null);
        notificationService.createRescheduleExpired(referral, blockedReservation);
        return true;
    }
}
