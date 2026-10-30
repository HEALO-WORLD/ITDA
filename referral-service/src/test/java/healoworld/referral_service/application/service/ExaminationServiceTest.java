package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

// 실제 DB로는 타이밍을 맞추기 어려운 "조회 후 다른 요청이 먼저 바꾼 경우"(조건부 UPDATE 0건) 분기를 검증한다
@ExtendWith(MockitoExtension.class)
class ExaminationServiceTest {

    @Mock
    private ExaminationRepository examinationRepository;

    @InjectMocks
    private ExaminationService examinationService;

    private Examination examination(UUID id, UUID referralId, UUID reservationId, ExaminationStatus status) {
        Examination examination = Examination.schedule(referralId, reservationId, UUID.randomUUID());
        ReflectionTestUtils.setField(examination, "id", id);
        ReflectionTestUtils.setField(examination, "status", status);
        return examination;
    }

    @Test
    @DisplayName("완료 UPDATE가 0건이지만 다른 요청이 이미 완료했다면 중복 요청으로 보고 현재 상태를 돌려준다")
    void completeLostRaceToSameRequest() {
        UUID id = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        given(examinationRepository.findById(id)).willReturn(
                Optional.of(examination(id, referralId, reservationId, ExaminationStatus.IN_PROGRESS)),
                Optional.of(examination(id, referralId, reservationId, ExaminationStatus.COMPLETED)));
        given(examinationRepository.completeIfInProgress(eq(id), any())).willReturn(0);

        ExaminationStatusChangeResponse response = examinationService.changeStatus(id, ExaminationStatus.COMPLETED);

        assertThat(response.previousStatus()).isEqualTo(ExaminationStatus.COMPLETED);
        assertThat(response.currentStatus()).isEqualTo(ExaminationStatus.COMPLETED);
    }

    @Test
    @DisplayName("예약 변경 UPDATE가 0건이고 그사이 검사가 시작됐다면 EXAMINATION_RESERVATION_NOT_CHANGEABLE")
    void changeReservationLostRaceToStart() {
        UUID id = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        UUID reservationA = UUID.randomUUID();
        given(examinationRepository.findByReferralId(referralId)).willReturn(
                Optional.of(examination(id, referralId, reservationA, ExaminationStatus.SCHEDULED)));
        given(examinationRepository.changeReservationIfScheduled(eq(id), any(), any(), any())).willReturn(0);
        given(examinationRepository.findById(id)).willReturn(
                Optional.of(examination(id, referralId, reservationA, ExaminationStatus.IN_PROGRESS)));

        assertThatThrownBy(() -> examinationService.updateReservation(referralId, UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
    }

    @Test
    @DisplayName("동시 검사 생성 중 UNIQUE 충돌이 발생하면 EXAMINATION_ALREADY_EXISTS")
    void createScheduledUniqueViolation() {

        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        // 최초 조회에서는 검사가 존재하지 않는 상황을 만든다
        given(examinationRepository.findByReferralId(referralId))
                .willReturn(Optional.empty());

        // 조회 이후 다른 요청이 먼저 검사를 생성했다고 가정한다
        // PostgreSQL UNIQUE 위반 코드인 23505를 강제로 발생시킨다
        DataIntegrityViolationException dbException =
                new DataIntegrityViolationException(
                        "중복 검사 생성",
                        new SQLException("UNIQUE 제약조건 위반", "23505")
                );

        given(examinationRepository.saveAndFlush(any(Examination.class)))
                .willThrow(dbException);

        // DB 오류가 프로젝트의 비즈니스 예외로 변환되는지 확인한다
        assertThatThrownBy(() ->
                examinationService.createScheduled(
                        referralId,
                        reservationId,
                        institutionId
                )
        )
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXAMINATION_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("UNIQUE 위반이 아닌 DB 오류는 원래 예외를 유지한다")
    void createScheduledOtherDatabaseViolation() {

        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        given(examinationRepository.findByReferralId(referralId))
                .willReturn(Optional.empty());

        // PostgreSQL NOT NULL 위반 코드인 23502를 발생시킨다
        DataIntegrityViolationException dbException =
                new DataIntegrityViolationException(
                        "필수 컬럼 누락",
                        new SQLException("NOT NULL 제약조건 위반", "23502")
                );

        given(examinationRepository.saveAndFlush(any(Examination.class)))
                .willThrow(dbException);

        // UNIQUE 위반이 아니라면 원래 발생한 예외를 그대로 전달해야 한다
        assertThatThrownBy(() ->
                examinationService.createScheduled(
                        referralId,
                        reservationId,
                        institutionId
                )
        )
                .isSameAs(dbException);
    }
}
