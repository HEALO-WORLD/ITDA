package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExaminationService {

    private final ExaminationRepository examinationRepository;

    public ExaminationResponse getByReferralId(UUID referralId) {
        return ExaminationResponse.from(findByReferralId(referralId));
    }

    // 최초 예약 확정 시 의뢰·예약 Saga가 호출한다. 호출한 쪽 트랜잭션에 참여하므로 의뢰·예약 확정과 함께 커밋/롤백된다
    // 같은 확정 처리가 다시 실행되면 기존 검사 ID를 그대로 돌려준다 (멱등)
    @Transactional
    public UUID createScheduled(UUID referralId, UUID reservationId, UUID examInstitutionId) {
        Optional<Examination> existing = examinationRepository.findByReferralId(referralId);
        if (existing.isPresent()) {
            if (existing.get().isLinkedTo(reservationId, examInstitutionId)) {
                return existing.get().getId();
            }
            // 다른 예약으로 이미 검사가 있다면 재예약이므로 updateReservation을 호출해야 한다
            throw new BaseException(ErrorCode.EXAMINATION_ALREADY_EXISTS);
        }

        // 조회와 저장 사이에 같은 의뢰의 검사가 동시에 저장되면 referral_id UNIQUE 제약이 막는다.
        // saveAndFlush로 INSERT를 즉시 실행해 제약 위반이 커밋 시점이 아니라 이 메서드 안에서 드러나게 한다
        return examinationRepository.saveAndFlush(
                Examination.schedule(referralId, reservationId, examInstitutionId)).getId();
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
                newExamInstitutionId, LocalDateTime.now());

        // 조회 이후 다른 요청이 먼저 검사를 시작했다면 0건이 갱신된다
        if (updated == 0 && !findById(examination.getId()).isLinkedTo(newReservationId, newExamInstitutionId)) {
            throw new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
        }
        return examination.getId();
    }

    // 검사 상태 변경. 같은 상태 요청은 DB를 바꾸지 않고 현재 상태를 그대로 돌려준다 (멱등)
    @Transactional
    public ExaminationStatusChangeResponse changeStatus(UUID examinationId, ExaminationStatus requestedStatus) {
        Examination examination = findById(examinationId);
        ExaminationStatus previousStatus = examination.getStatus();

        if (previousStatus == requestedStatus) {
            return ExaminationStatusChangeResponse.unchanged(examination);
        }
        if (!previousStatus.canTransitionTo(requestedStatus)) {
            throw new BaseException(ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION);
        }
        if (requestedStatus == ExaminationStatus.IN_PROGRESS) {
            // 시작 조건(의뢰 CONFIRMED, 예약 CONFIRMED, 의뢰의 현재 예약 = 검사의 예약)은
            // Referral·Reservation 엔티티가 병합된 뒤 연동한다. 검증할 수 없는 동안에는 시작을 허용하지 않는다
            throw new BaseException(ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        }

        int updated = examinationRepository.completeIfInProgress(examinationId, LocalDateTime.now());

        // 조건부 UPDATE는 영속성 컨텍스트를 거치지 않으므로 DB의 최신 값을 다시 읽는다
        Examination current = findById(examinationId);
        if (updated == 1) {
            return ExaminationStatusChangeResponse.of(previousStatus, current);
        }
        // 동시에 들어온 같은 요청이 먼저 완료 처리했다면 중복 요청으로 보고 현재 상태를 돌려준다
        if (current.getStatus() == requestedStatus) {
            return ExaminationStatusChangeResponse.unchanged(current);
        }
        throw new BaseException(ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION);
    }

    private Examination findById(UUID examinationId) {
        return examinationRepository.findById(examinationId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
    }

    private Examination findByReferralId(UUID referralId) {
        return examinationRepository.findByReferralId(referralId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
    }
}
