package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.command.SlotCommandType;
import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
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

import java.time.Clock;
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
    private final IncidentReservationRepository incidentReservationRepository;   // 취소 시 예약 행 잠금 (의뢰 → 예약 → 검사 순서)
    private final ExaminationRepository examinationRepository;                   // 취소 시 검사 상태 확인
    private final Clock clock;                                                   // 취소 2시간 제한의 현재 시각 (한국 시간)

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

    // 예약 취소 (의뢰기관 사용자의 취소. 장비 장애·재조정·보상 같은 시스템 처리는 이 메서드를 쓰지 않는다)
    // - 잠금 순서: 의뢰 → 예약 → 검사. 검사 시작(ExaminationService.lockForStart)·장비 장애 처리와 같은 순서라 교착이 생기지 않고,
    //   의뢰 잠금을 먼저 잡으므로 검사 시작과 취소는 한쪽이 커밋된 뒤에 다른 쪽이 진행된다
    // - 모든 검증(권한·2시간 제한·현재 예약·검사 상태)은 상태 변경·Outbox 저장보다 먼저 한다.
    //   검증에 실패하거나 조회 중 오류가 나면 이 트랜잭션 전체가 롤백되어 슬롯 해제 명령(RELEASE_SLOT)도 남지 않는다
    public void cancelReservation(UUID userId, UUID institutionId, RoleType role, UUID reservationId) {
        UUID referralId = reservationRepository.findReferralIdById(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        Referral referral = referralRepository.findByIdForUpdate(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

        // 의뢰 다음에 예약을 잠근다 (잠금 이후 처음 읽으므로 최신 상태다)
        Reservation reservation = incidentReservationRepository.findByIdForUpdate(reservationId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

        if (!(institutionId.equals(referral.getReferringInstitutionId())
                || role == RoleType.ADMIN)
        ) {
            throw new BaseException(ErrorCode.RESERVATION_NOT_FOUND);
        }

        // 예약 시작 시각은 한국 시간 LocalDateTime이므로 현재 시각도 한국 시간으로 구한다
        LocalDateTime now = LocalDateTime.now(clock);

        if (!now.isBefore(reservation.getStartTime().minusHours(2))) {
            throw new BaseException(ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
        }

        // 의뢰가 지금 가리키는 예약만 취소한다 (재예약 이전의 예약 등은 취소 대상이 아니다)
        if (!reservationId.equals(referral.getCurrentReservationId())) {
            throw new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
        }

        validateExaminationNotStarted(referralId, reservationId);

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

    // 취소할 예약의 검사가 아직 시작 전(SCHEDULED)인지 확인한다. 의뢰·예약 다음에 검사를 잠근 뒤 읽는다
    // - IN_PROGRESS·COMPLETED: 409 EXAMINATION_CANCEL_NOT_ALLOWED (정책 위반)
    // - 검사가 없거나 이 예약에 연결돼 있지 않음: 상태를 확인할 수 없으므로 거부한다 (fail-closed, 409 INVALID_RESERVATION_STATUS)
    //   정상 흐름에서는 예약 확정(SlotBooked)과 같은 트랜잭션에서 이 예약에 연결된 검사가 만들어진다
    // - 잠금 대기 시간 초과·DB 오류는 BaseException이 아니므로 그대로 전파되어 롤백되고 500으로 응답한다 (시스템 장애)
    private void validateExaminationNotStarted(UUID referralId, UUID reservationId) {
        Examination examination = examinationRepository.findByReferralIdForUpdate(referralId)
                .orElseThrow(() -> {
                    log.warn("취소 거부: 의뢰의 검사가 없어 검사 상태를 확인할 수 없음. referralId={}, reservationId={}",
                            referralId, reservationId);
                    return new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
                });
        if (!reservationId.equals(examination.getReservationId())) {
            log.warn("취소 거부: 검사가 다른 예약에 연결됨. referralId={}, reservationId={}, examinationReservationId={}",
                    referralId, reservationId, examination.getReservationId());
            throw new BaseException(ErrorCode.INVALID_RESERVATION_STATUS);
        }
        if (examination.getStatus() != ExaminationStatus.SCHEDULED) {
            throw new BaseException(ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);
        }
    }
}
