package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationListResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;

import healoworld.referral_service.support.DomainFixtures;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExaminationServiceTest {

    @Mock
    private ExaminationRepository examinationRepository;

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ExaminationResultRepository examinationResultRepository;

    @Mock
    private IncidentReservationRepository incidentReservationRepository;

    @InjectMocks
    private ExaminationService examinationService;

    @Test
    @DisplayName("검사 시작은 의뢰 → 예약 → 검사 순서로 잠근 뒤 검증하고 UPDATE한다 (장비 장애 처리와 같은 잠금 순서)")
    void startLocksReferralReservationExaminationInOrder() {
        Referral referral = DomainFixtures.referralIn(ReferralStatus.CONFIRMED, UUID.randomUUID());
        Reservation reservation = DomainFixtures.reservationIn(ReservationStatus.CONFIRMED, referral);
        ReflectionTestUtils.setField(referral, "currentReservationId", reservation.getId());
        UUID id = UUID.randomUUID();
        UUID institutionId = reservation.getExamInstitutionId();   // 검사기관은 예약의 검사기관과 같아야 시작할 수 있다
        Examination scheduled = examination(id, referral.getId(), reservation.getId(), ExaminationStatus.SCHEDULED, institutionId);
        Examination started = examination(id, referral.getId(), reservation.getId(), ExaminationStatus.IN_PROGRESS, institutionId);

        given(examinationRepository.findReferralIdById(id)).willReturn(Optional.of(referral.getId()));
        given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
        given(incidentReservationRepository.findByIdForUpdate(reservation.getId())).willReturn(Optional.of(reservation));
        given(examinationRepository.findByReferralIdForUpdate(referral.getId())).willReturn(Optional.of(scheduled));
        given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
        given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));
        given(examinationRepository.startIfScheduled(eq(id), eq(reservation.getId()), any())).willReturn(1);
        given(examinationRepository.findById(id)).willReturn(Optional.of(started));

        ExaminationStatusChangeResponse response = examinationService.changeStatus(
                id, institutionId, RoleType.EXAMINER, ExaminationStatus.IN_PROGRESS);

        assertThat(response.currentStatus()).isEqualTo(ExaminationStatus.IN_PROGRESS);
        var inOrder = inOrder(referralRepository, incidentReservationRepository, examinationRepository);
        inOrder.verify(referralRepository).findByIdForUpdate(referral.getId());
        inOrder.verify(incidentReservationRepository).findByIdForUpdate(reservation.getId());
        inOrder.verify(examinationRepository).findByReferralIdForUpdate(referral.getId());
        inOrder.verify(examinationRepository).startIfScheduled(eq(id), eq(reservation.getId()), any());
        // 잠금 전에 검사 엔티티를 읽지 않는다 (영속성 컨텍스트에 이전 상태가 남지 않도록).
        // findById는 UPDATE 이후 최신 상태를 응답하기 위한 1번뿐이다
        inOrder.verify(examinationRepository).findById(id);
        verify(examinationRepository, times(1)).findById(id);
    }

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

        private final LocalDateTime examDate =
                LocalDateTime.of(2026, 11, 5, 0, 0);

        private final LocalDateTime startTime =
                LocalDateTime.of(2026, 11, 5, 10, 0);

        private final LocalDateTime endTime =
                LocalDateTime.of(2026, 11, 5, 10, 30);

        /**
         * 의뢰·예약 정보를 Mock으로 준비한다.
         *
         * @param registeredIds 결과가 등록된 검사 ID 목록
         * @param examinations  조회 대상 검사 목록
         */
        private void mockRelatedData(
                List<UUID> registeredIds,
                Examination... examinations
        ) {

            List<Referral> referrals = new ArrayList<>();
            List<Reservation> reservations = new ArrayList<>();

            for (Examination examination : examinations) {

                // 의뢰 정보
                Referral referral = mock(Referral.class);

                given(referral.getId())
                        .willReturn(examination.getReferralId());

                given(referral.getExamTypeId())
                        .willReturn(UUID.randomUUID());

                referrals.add(referral);

                // 예약 정보
                Reservation reservation = mock(Reservation.class);

                given(reservation.getId())
                        .willReturn(examination.getReservationId());

                given(reservation.getExamDate())
                        .willReturn(examDate);

                given(reservation.getStartTime())
                        .willReturn(startTime);

                given(reservation.getEndTime())
                        .willReturn(endTime);

                given(reservation.getReservationStatus())
                        .willReturn(ReservationStatus.CONFIRMED);

                given(reservation.getEquipmentId())
                        .willReturn(UUID.randomUUID());

                reservations.add(reservation);
            }

            given(referralRepository.findAllById(any()))
                    .willReturn(referrals);

            given(reservationRepository.findAllById(any()))
                    .willReturn(reservations);

            given(examinationResultRepository.findRegisteredExaminationIds(any()))
                    .willReturn(registeredIds);
        }

        @Test
        @DisplayName("EXAMINER는 자기 기관의 검사 목록과 관련 정보를 조회할 수 있다")
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

            mockRelatedData(List.of(), first, second);

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

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::reservationStatus)
                    .containsExactly(
                            ReservationStatus.CONFIRMED,
                            ReservationStatus.CONFIRMED
                    );

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::examDate)
                    .containsExactly(examDate, examDate);

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::startTime)
                    .containsExactly(startTime, startTime);

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::endTime)
                    .containsExactly(endTime, endTime);

            assertThat(response.contents())
                    .extracting(ExaminationListResponse::resultRegistered)
                    .containsExactly(false, false);

            assertThat(response.contents())
                    .allSatisfy(item -> {
                        assertThat(item.examTypeId()).isNotNull();
                        assertThat(item.equipmentId()).isNotNull();
                    });

            verify(examinationRepository)
                    .findByExamInstitutionId(institutionId, pageable);

            verify(referralRepository)
                    .findAllById(any());

            verify(reservationRepository)
                    .findAllById(any());

            verify(examinationResultRepository)
                    .findRegisteredExaminationIds(any());
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

            verifyNoInteractions(
                    examinationRepository,
                    referralRepository,
                    reservationRepository,
                    examinationResultRepository
            );
        }

        @Test
        @DisplayName("소속 기관에 검사가 없으면 빈 목록을 반환하고 추가 조회하지 않는다")
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

            verifyNoInteractions(
                    referralRepository,
                    reservationRepository,
                    examinationResultRepository
            );
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

            mockRelatedData(List.of(), examination);

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

        @Test
        @DisplayName("결과가 등록된 검사는 resultRegistered가 true로 반환된다")
        void getExaminations_resultRegistered() {

            // given
            UUID institutionId = UUID.randomUUID();

            Examination examination = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.COMPLETED,
                    institutionId
            );

            PageRequest pageable = PageRequest.of(0, 10);

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(
                    new PageImpl<>(
                            List.of(examination),
                            pageable,
                            1
                    )
            );

            mockRelatedData(
                    List.of(examination.getId()),
                    examination
            );

            // when
            PagedResponse<ExaminationListResponse> response =
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    );

            // then
            assertThat(response.contents()).hasSize(1);

            assertThat(response.contents().get(0).resultRegistered())
                    .isTrue();
        }

        @Test
        @DisplayName("여러 검사 중 결과가 등록된 검사만 true로 반환된다")
        void getExaminations_partialResults() {

            // given
            UUID institutionId = UUID.randomUUID();

            Examination first = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.COMPLETED,
                    institutionId
            );

            Examination second = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.COMPLETED,
                    institutionId
            );

            PageRequest pageable = PageRequest.of(0, 10);

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(
                    new PageImpl<>(
                            List.of(first, second),
                            pageable,
                            2
                    )
            );

            mockRelatedData(
                    List.of(first.getId()),
                    first,
                    second
            );

            // when
            PagedResponse<ExaminationListResponse> response =
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    );

            // then
            assertThat(response.contents())
                    .extracting(ExaminationListResponse::resultRegistered)
                    .containsExactly(true, false);
        }

        @Test
        @DisplayName("의뢰 정보가 없으면 예외가 발생한다")
        void getExaminations_missingReferral() {

            // given
            UUID institutionId = UUID.randomUUID();

            Examination examination = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.SCHEDULED,
                    institutionId
            );

            PageRequest pageable = PageRequest.of(0, 10);

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(
                    new PageImpl<>(
                            List.of(examination),
                            pageable,
                            1
                    )
            );

            given(referralRepository.findAllById(any()))
                    .willReturn(List.of());

            Reservation reservation = mock(Reservation.class);

            given(reservation.getId())
                    .willReturn(examination.getReservationId());

            given(reservationRepository.findAllById(any()))
                    .willReturn(List.of(reservation));

            given(examinationResultRepository.findRegisteredExaminationIds(any()))
                    .willReturn(List.of());

            // when & then
            assertThatThrownBy(() ->
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    )
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("의뢰 또는 예약");
        }

        @Test
        @DisplayName("예약 정보가 없으면 예외가 발생한다")
        void getExaminations_missingReservation() {

            // given
            UUID institutionId = UUID.randomUUID();

            Examination examination = examination(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    ExaminationStatus.SCHEDULED,
                    institutionId
            );

            PageRequest pageable = PageRequest.of(0, 10);

            given(examinationRepository.findByExamInstitutionId(
                    institutionId,
                    pageable
            )).willReturn(
                    new PageImpl<>(
                            List.of(examination),
                            pageable,
                            1
                    )
            );

            Referral referral = mock(Referral.class);

            given(referral.getId())
                    .willReturn(examination.getReferralId());

            given(referralRepository.findAllById(any()))
                    .willReturn(List.of(referral));

            given(reservationRepository.findAllById(any()))
                    .willReturn(List.of());

            given(examinationResultRepository.findRegisteredExaminationIds(any()))
                    .willReturn(List.of());

            // when & then
            assertThatThrownBy(() ->
                    examinationService.getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable
                    )
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("의뢰 또는 예약");
        }
    }
}
