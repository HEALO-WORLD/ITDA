package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationListResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExaminationServiceTest {

    @Mock
    private ExaminationRepository examinationRepository;

    @InjectMocks
    private ExaminationService examinationService;

    // ==============================
    // 테스트 데이터 생성
    // ==============================

    private Examination examination(
            UUID id,
            UUID referralId,
            UUID reservationId,
            ExaminationStatus status,
            UUID institutionId
    ) {
        Examination examination = Examination.schedule(
                referralId,
                reservationId,
                institutionId
        );

        ReflectionTestUtils.setField(examination, "id", id);
        ReflectionTestUtils.setField(examination, "status", status);

        return examination;
    }

    // ==============================
    // 검사 상태 변경 및 동시성 처리
    // ==============================

    @Test
    @DisplayName("완료 UPDATE가 0건이지만 다른 요청이 이미 완료했다면 중복 요청으로 처리한다")
    void completeLostRaceToSameRequest() {

        // given
        UUID id = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        Examination inProgress = examination(
                id,
                referralId,
                reservationId,
                ExaminationStatus.IN_PROGRESS,
                institutionId
        );

        Examination completed = examination(
                id,
                referralId,
                reservationId,
                ExaminationStatus.COMPLETED,
                institutionId
        );

        given(examinationRepository.findById(id))
                .willReturn(
                        Optional.of(inProgress),
                        Optional.of(completed)
                );

        given(examinationRepository.completeIfInProgress(eq(id), any()))
                .willReturn(0);

        // when
        ExaminationStatusChangeResponse response =
                examinationService.changeStatus(
                        id,
                        institutionId,
                        RoleType.EXAMINER,
                        ExaminationStatus.COMPLETED
                );

        // then
        assertThat(response.previousStatus())
                .isEqualTo(ExaminationStatus.COMPLETED);

        assertThat(response.currentStatus())
                .isEqualTo(ExaminationStatus.COMPLETED);
    }

    @Test
    @DisplayName("예약 변경 중 검사가 시작되면 예약 변경을 거부한다")
    void changeReservationLostRaceToStart() {

        // given
        UUID id = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Examination scheduled = examination(
                id,
                referralId,
                reservationId,
                ExaminationStatus.SCHEDULED,
                UUID.randomUUID()
        );

        Examination inProgress = examination(
                id,
                referralId,
                reservationId,
                ExaminationStatus.IN_PROGRESS,
                UUID.randomUUID()
        );

        given(examinationRepository.findByReferralId(referralId))
                .willReturn(Optional.of(scheduled));

        given(examinationRepository.changeReservationIfScheduled(
                eq(id),
                any(),
                any(),
                any()
        )).willReturn(0);

        given(examinationRepository.findById(id))
                .willReturn(Optional.of(inProgress));

        // when & then
        assertThatThrownBy(() ->
                examinationService.updateReservation(
                        referralId,
                        UUID.randomUUID(),
                        UUID.randomUUID()
                )
        )
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
    }

    // ==============================
    // 검사 중복 생성 및 DB 예외 처리
    // ==============================

    @Test
    @DisplayName("동시 검사 생성 중 UNIQUE 충돌이 발생하면 중복 생성 예외를 반환한다")
    void createScheduledUniqueViolation() {

        // given
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        given(examinationRepository.findByReferralId(referralId))
                .willReturn(Optional.empty());

        DataIntegrityViolationException dbException =
                new DataIntegrityViolationException(
                        "중복 검사 생성",
                        new SQLException("UNIQUE 제약조건 위반", "23505")
                );

        given(examinationRepository.saveAndFlush(any(Examination.class)))
                .willThrow(dbException);

        // when & then
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

        // given
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        given(examinationRepository.findByReferralId(referralId))
                .willReturn(Optional.empty());

        DataIntegrityViolationException dbException =
                new DataIntegrityViolationException(
                        "필수 컬럼 누락",
                        new SQLException("NOT NULL 위반", "23502")
                );

        given(examinationRepository.saveAndFlush(any(Examination.class)))
                .willThrow(dbException);

        // when & then
        assertThatThrownBy(() ->
                examinationService.createScheduled(
                        referralId,
                        reservationId,
                        institutionId
                )
        ).isSameAs(dbException);
    }

    // ==============================
    // 검사기관별 검사 목록 조회
    // ==============================

    @Nested
    @DisplayName("검사기관별 검사 목록 조회")
    class GetExaminationsTest {

        @Test
        @DisplayName("EXAMINER는 자기 기관의 검사 목록을 조회할 수 있다")
        void getExaminations_success() {

            // given
            UUID institutionId = UUID.randomUUID();

            Examination first = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.SCHEDULED,
                    institutionId
            );

            Examination second = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.IN_PROGRESS,
                    institutionId
            );

            PageRequest pageable = PageRequest.of(0, 10);

            Page<Examination> page = new PageImpl<>(
                    List.of(first, second),
                    pageable,
                    2
            );

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(page);

            // when
            PagedResponse<ExaminationListResponse> response =
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    );

            // then
            assertThat(response.contents()).hasSize(2);
            assertThat(response.totalElements()).isEqualTo(2);

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::examinationId)
                    .containsExactly(
                            first.getId(),
                            second.getId()
                    );

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::status)
                    .containsExactly(
                            ExaminationStatus.SCHEDULED,
                            ExaminationStatus.IN_PROGRESS
                    );

            verify(examinationRepository)
                    .findByExamInstitutionId(institutionId, pageable);
        }

        @Test
        @DisplayName("EXAMINER가 아닌 사용자는 검사 목록을 조회할 수 없다")
        void getExaminations_accessDenied() {

            // given
            UUID institutionId = UUID.randomUUID();
            PageRequest pageable = PageRequest.of(0, 10);

            // when & then
            assertThatThrownBy(() ->
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.REQUESTER,
                            pageable
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_ACCESS_DENIED);

            verify(examinationRepository, never())
                    .findByExamInstitutionId(any(), any());
        }

        @Test
        @DisplayName("소속 기관에 검사가 없으면 빈 목록을 반환한다")
        void getExaminations_empty() {

            // given
            UUID institutionId = UUID.randomUUID();
            PageRequest pageable = PageRequest.of(0, 10);

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(Page.empty(pageable));

            // when
            PagedResponse<ExaminationListResponse> response =
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    );

            // then
            assertThat(response.contents()).isEmpty();
            assertThat(response.totalElements()).isZero();
            assertThat(response.totalPages()).isZero();
        }

        @Test
        @DisplayName("검사 목록 조회 시 페이지네이션 정보가 유지된다")
        void getExaminations_pagination() {

            // given
            UUID institutionId = UUID.randomUUID();
            PageRequest pageable = PageRequest.of(1, 2);

            Examination examination = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.COMPLETED,
                    institutionId
            );

            Page<Examination> page = new PageImpl<>(
                    List.of(examination),
                    pageable,
                    3
            );

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(page);

            // when
            PagedResponse<ExaminationListResponse> response =
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    );

            // then
            assertThat(response.contents()).hasSize(1);
            assertThat(response.totalElements()).isEqualTo(3);
            assertThat(response.totalPages()).isEqualTo(2);
            assertThat(response.size()).isEqualTo(2);
            assertThat(response.number()).isEqualTo(1);
        }
    }
}