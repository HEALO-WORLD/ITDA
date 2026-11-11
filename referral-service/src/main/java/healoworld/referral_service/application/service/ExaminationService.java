package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExaminationService {

    // PostgreSQL의 UNIQUE 제약조건 위반을 나타내는 SQLSTATE
    // 같은 의뢰의 검사가 동시에 생성될 때 발생하는 중복 저장 오류를 구분하기 위해 사용한다
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final ExaminationRepository examinationRepository;
    private final ReferralRepository referralRepository;
    private final ReservationRepository reservationRepository;
    // 예약 행 잠금(FOR UPDATE). 장비 장애 처리가 쓰는 잠금 조회를 검사 시작에서도 재사용해 잠금 순서를 맞춘다
    private final IncidentReservationRepository incidentReservationRepository;
    // 검사 시작·완료·예약 연결 변경 시각 (한국 시간)
    private final Clock clock;

    // 최초 예약 확정 시 의뢰·예약 Saga가 호출한다. 호출한 쪽 트랜잭션에 참여하므로 의뢰·예약 확정과 함께 커밋/롤백된다
    // 같은 확정 처리가 다시 실행되면 기존 검사 ID를 그대로 돌려준다 (멱등)
    @Transactional
    public UUID createScheduled(UUID referralId, UUID reservationId, UUID examInstitutionId) {
        Optional<Examination> existing = examinationRepository.findByReferralId(referralId);
        if (existing.isPresent()) {
            if (existing.get().isLinkedTo(reservationId, examInstitutionId)) {
                return existing.get().getId();
            }
            // 다른 예약으로 검사가 존재한다면 신규 생성이 아닌 재예약 처리가 필요하다
            throw new BaseException(ErrorCode.EXAMINATION_ALREADY_EXISTS);
        }

        try {
            // saveAndFlush()를 사용해 INSERT를 즉시 실행하여
            // DB 제약조건 위반이 발생하면 이 메서드 안에서 감지할 수 있도록 한다
            return examinationRepository.saveAndFlush(
                    Examination.schedule(referralId, reservationId, examInstitutionId)
            ).getId();
        } catch (DataIntegrityViolationException e) {

            // 조회와 저장 사이에 다른 요청이 같은 의뢰의 검사를 먼저 생성했다면
            // DB의 UNIQUE 제약조건에 의해 INSERT가 실패할 수 있다
            if (isUniqueViolation(e)) {

                // DB 중복 오류를 프로젝트에서 사용하는 비즈니스 예외로 변환한다
                // 실패한 트랜잭션에서 다시 조회하지 않고 예외를 던져 롤백한다
                throw new BaseException(ErrorCode.EXAMINATION_ALREADY_EXISTS);
            }

            // UNIQUE 위반이 아닌 다른 DB 제약조건 오류는
            // 중복 생성 오류로 잘못 처리하지 않고 원래 예외를 그대로 전달한다
            throw e;
        }
    }

    // 재예약 확정 시 의뢰·예약 Saga가 호출한다. 새 검사를 만들지 않고 기존 검사를 새 예약에 연결한다
    @Transactional
    public UUID updateReservation(UUID referralId, UUID newReservationId, UUID newExamInstitutionId) {
        Examination examination = findByReferralId(referralId);
        if (examination.isLinkedTo(newReservationId, newExamInstitutionId)) {
            return examination.getId();
        }
        if (examination.getStatus() != ExaminationStatus.SCHEDULED) {
            throw new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
        }

        int updated = examinationRepository.changeReservationIfScheduled(examination.getId(), newReservationId,
                newExamInstitutionId, LocalDateTime.now(clock));

        // 조회 이후 다른 요청이 먼저 검사를 시작했다면 0건이 갱신된다
        if (updated == 0 && !findById(examination.getId()).isLinkedTo(newReservationId, newExamInstitutionId)) {
            throw new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
        }
        return examination.getId();
    }

    // 검사 상태 변경. 같은 상태 요청은 DB를 바꾸지 않고 현재 상태를 그대로 돌려준다 (멱등)
    @Transactional
    public ExaminationStatusChangeResponse changeStatus(
            UUID examinationId,
            UUID institutionId,
            RoleType role,
            ExaminationStatus requestedStatus
    ) {
        // 검사 시작은 장비 장애 처리와 같은 순서(의뢰 → 예약 → 검사)로 잠근 뒤의 최신 상태로 판단한다
        Examination examination = requestedStatus == ExaminationStatus.IN_PROGRESS
                ? lockForStart(examinationId)
                : findById(examinationId);

        // 1. EXAMINER 역할인지 확인
        if (role != RoleType.EXAMINER) {
            throw new BaseException(ErrorCode.EXAMINATION_ACCESS_DENIED);
        }

        // 2. 해당 검사를 수행하는 기관 소속인지 확인
        if (!examination.getExamInstitutionId().equals(institutionId)) {
            throw new BaseException(ErrorCode.EXAMINATION_ACCESS_DENIED);
        }

        // 3. 권한 확인 후 기존 상태 변경 로직 실행
        ExaminationStatus previousStatus = examination.getStatus();

        // 같은 상태 요청은 변경하지 않음 (멱등성)
        if (previousStatus == requestedStatus) {
            return ExaminationStatusChangeResponse.unchanged(examination);
        }

        // 허용되지 않는 상태 전이 차단
        if (!previousStatus.canTransitionTo(requestedStatus)) {
            throw new BaseException(
                    ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION
            );
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int updated;

        if (requestedStatus == ExaminationStatus.IN_PROGRESS) {

            // 검사를 시작할 수 있는 유효한 예약인지 확인
            validateCanStart(examination);

            // SCHEDULED → IN_PROGRESS
            updated = examinationRepository.startIfScheduled(
                    examinationId,
                    examination.getReservationId(),
                    now
            );

        } else {

            // IN_PROGRESS → COMPLETED
            updated = examinationRepository.completeIfInProgress(
                    examinationId,
                    now
            );
        }

        // 조건부 UPDATE 이후 최신 상태 조회
        Examination current = findById(examinationId);

        if (updated == 1) {
            return ExaminationStatusChangeResponse.of(
                    previousStatus,
                    current
            );
        }

        // 동시 요청으로 이미 동일 상태가 된 경우
        if (current.getStatus() == requestedStatus) {
            return ExaminationStatusChangeResponse.unchanged(current);
        }

        // 검사를 시작하려는 동안 예약 연결이 변경된 경우
        if (requestedStatus == ExaminationStatus.IN_PROGRESS
                && !current.getReservationId()
                .equals(examination.getReservationId())) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_START_NOT_ALLOWED
            );
        }

        throw new BaseException(
                ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION
        );
    }

    @Transactional
    public UUID syncScheduledExamination(
            UUID referralId,
            UUID reservationId,
            UUID examInstitutionId
    ) {
        // 최초 예약인지 재예약인지 구분
        if (examinationRepository.findByReferralId(referralId).isEmpty()) {
            return createScheduled(
                    referralId,
                    reservationId,
                    examInstitutionId
            );
        }

        // 기존 검사 기록이 있으면 새 예약에 연결
        return updateReservation(
                referralId,
                reservationId,
                examInstitutionId
        );
    }

    private Examination findById(UUID examinationId) {
        return examinationRepository.findById(examinationId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
    }

    // 검사 시작 전 잠금: 의뢰 → 현재 예약 → 검사 순서로 FOR UPDATE (장비 장애 처리 IncidentProcessingService와 같은 순서)
    // - 장애 처리가 먼저 잠그면 커밋까지 기다렸다가 BLOCKED 예약·NEEDS_RESCHEDULE 의뢰를 보고 시작을 거부한다
    // - 검사 시작이 먼저 잠그면 장애 처리가 기다렸다가 IN_PROGRESS 검사를 보고 재조정에서 제외한다
    // 검사 엔티티는 잠금을 잡은 뒤 처음 읽는다. 먼저 읽어 두면 잠금 조회가 영속성 컨텍스트의 이전 상태를 그대로 돌려주기 때문이다
    // 이후 validateCanStart()의 의뢰·예약 조회도 여기서 잠그며 읽은 최신 엔티티를 그대로 쓴다
    private Examination lockForStart(UUID examinationId) {
        UUID referralId = examinationRepository.findReferralIdById(examinationId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
        // 의뢰·예약이 없으면 잠그지 않고 넘어가며, 시작 가능 여부는 validateCanStart()가 판단한다
        referralRepository.findByIdForUpdate(referralId)
                .map(Referral::getCurrentReservationId)
                .ifPresent(incidentReservationRepository::findByIdForUpdate);
        return examinationRepository.findByReferralIdForUpdate(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
    }

    private Examination findByReferralId(UUID referralId) {
        return examinationRepository.findByReferralId(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
    }

    // DB 제약조건 위반 예외가 PostgreSQL의 UNIQUE 위반인지 확인한다.
    private boolean isUniqueViolation(DataIntegrityViolationException e) {

        // Spring 예외 내부에 감싸진 가장 구체적인 원인을 확인한다
        // 실제 원인이 SQLException이면 PostgreSQL의 SQLSTATE를 가져올 수 있다
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sqlException

                // SQLSTATE가 23505이면 UNIQUE 제약조건 위반으로 판단한다
                && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState());
    }

    // 검증 메서드
    private void validateCanStart(Examination examination) {

        // 1. 검사에 연결된 의뢰 조회
        Referral referral = referralRepository
                .findById(examination.getReferralId())
                .orElseThrow(() -> new BaseException(
                        ErrorCode.EXAMINATION_START_NOT_ALLOWED
                ));

        UUID currentReservationId = referral.getCurrentReservationId();

        // 2. 의뢰 상태 및 현재 예약 ID 확인
        if (referral.getReferralStatus() != ReferralStatus.CONFIRMED
                || currentReservationId == null
                || !currentReservationId.equals(examination.getReservationId())) {

            throw new BaseException(
                    ErrorCode.EXAMINATION_START_NOT_ALLOWED
            );
        }

        // 3. 현재 예약 조회
        Reservation reservation = reservationRepository
                .findById(currentReservationId)
                .orElseThrow(() -> new BaseException(
                        ErrorCode.EXAMINATION_START_NOT_ALLOWED
                ));

        // 4. 예약 상태 확인
        if (reservation.getReservationStatus() != ReservationStatus.CONFIRMED) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_START_NOT_ALLOWED
            );
        }

        // 5. 예약과 의뢰의 연결 확인
        if (!reservation.getReferral().getId().equals(referral.getId())) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_START_NOT_ALLOWED
            );
        }

        // 6. 검사기관 일치 여부 확인
        if (!reservation.getExamInstitutionId()
                .equals(examination.getExamInstitutionId())) {

            throw new BaseException(
                    ErrorCode.EXAMINATION_START_NOT_ALLOWED
            );
        }
    }
}
