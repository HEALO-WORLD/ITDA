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
import org.springframework.test.util.ReflectionTestUtils;

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
}
