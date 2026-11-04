package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import healoworld.referral_service.support.ConditionalUpdateStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.EXAM_START;
import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservation;
import static healoworld.referral_service.support.DomainFixtures.reservationRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private ReferralHistoryRepository referralHistoryRepository;

    @Mock
    private ReservationService reservationService;

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReferralService referralService;

    // 조건부 UPDATE를 DB처럼 흉내 낸다 (기대 상태가 맞으면 바꾸고 1, 아니면 0)
    private ConditionalUpdateStubs updates;

    @BeforeEach
    void setUp() {
        updates = new ConditionalUpdateStubs(reservationRepository, referralRepository);
    }

    @Nested
    @DisplayName("createReferral")
    class CreateReferral {
        @Test
        @DisplayName("새 멱등키면 의뢰와 첫 예약을 생성하고 예약을 연결한다")
        void createWithReservation() {
            CreateReservationRequest reservationRequest = reservationRequest();
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest);
            Reservation reservation = reservation(referralIn(ReferralStatus.PENDING, USER_ID), 1);

            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(reservationService.createReservation(any(UUID.class), any(Referral.class), eq(reservationRequest))).willReturn(reservation);

            referralService.createReferral(USER_ID, request);

            ArgumentCaptor<Referral> captor = ArgumentCaptor.forClass(Referral.class);
            verify(referralRepository).saveAndFlush(captor.capture());
            Referral saved = captor.getValue();

            assertThat(saved.getReferrerId()).isEqualTo(USER_ID);
            assertThat(saved.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(saved.getIdempotencyKey()).isEqualTo(request.getIdempotencyKey());
            assertThat(saved.getCurrentReservationId()).isEqualTo(reservation.getId());
            ArgumentCaptor<UUID> eventIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(reservationService).createReservation(eventIdCaptor.capture(), eq(saved), eq(reservationRequest));

            // 생성 이력은 HoldRequested와 같은 eventId로 남는다
            ArgumentCaptor<ReferralHistory> historyCaptor = ArgumentCaptor.forClass(ReferralHistory.class);
            verify(referralHistoryRepository).save(historyCaptor.capture());
            ReferralHistory history = historyCaptor.getValue();
            assertThat(history.getFromStatus()).isNull();
            assertThat(history.getToStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(history.getReason()).isEqualTo(Reason.REFERRAL_CREATED);
            assertThat(history.getEventId()).isEqualTo(eventIdCaptor.getValue());
            assertThat(history.getChangedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("이미 처리된 멱등키로 같은 내용이 오면 기존 의뢰를 반환하고 의뢰와 예약을 새로 만들지 않는다")
        void duplicatedIdempotencyKey() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            Reservation first = storedWithFirstReservation(existing);

            assertThat(referralService.createReferral(USER_ID, sameRequestAs(existing, first.getSlotId())).getId())
                    .isEqualTo(existing.getId());

            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("같은 멱등키인데 환자 이름이 다르면 IDEMPOTENCY_CONFLICT")
        void differentPatientName() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            Reservation first = storedWithFirstReservation(existing);
            CreateReferralRequest request = requestOf(existing.getIdempotencyKey(), existing.getReferringInstitutionId(),
                    existing.getExamTypeId(), "다른환자", first.getSlotId());

            assertConflictWithoutCreating(() -> referralService.createReferral(USER_ID, request));
        }

        @Test
        @DisplayName("같은 멱등키인데 검사 종류가 다르면 IDEMPOTENCY_CONFLICT")
        void differentExamType() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            Reservation first = storedWithFirstReservation(existing);
            CreateReferralRequest request = requestOf(existing.getIdempotencyKey(), existing.getReferringInstitutionId(),
                    UUID.randomUUID(), existing.getPatientName(), first.getSlotId());

            assertConflictWithoutCreating(() -> referralService.createReferral(USER_ID, request));
        }

        @Test
        @DisplayName("같은 멱등키인데 첫 예약의 슬롯과 다르면 IDEMPOTENCY_CONFLICT")
        void differentSlot() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            storedWithFirstReservation(existing);

            assertConflictWithoutCreating(() -> referralService.createReferral(USER_ID, sameRequestAs(existing, UUID.randomUUID())));
        }

        @Test
        @DisplayName("같은 멱등키를 다른 사용자가 쓰면 기존 의뢰를 넘겨주지 않고 IDEMPOTENCY_CONFLICT")
        void otherUser() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            Reservation first = storedWithFirstReservation(existing);

            assertConflictWithoutCreating(() -> referralService.createReferral(OTHER_USER_ID, sameRequestAs(existing, first.getSlotId())));
        }

        @Test
        @DisplayName("의뢰는 있는데 첫 예약을 찾을 수 없으면 IDEMPOTENCY_CONFLICT")
        void firstReservationMissing() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findByIdempotencyKey(existing.getIdempotencyKey())).willReturn(Optional.of(existing));
            given(reservationRepository.findByReferralIdAndAttemptNo(existing.getId(), 1)).willReturn(Optional.empty());

            assertConflictWithoutCreating(() -> referralService.createReferral(USER_ID, sameRequestAs(existing, UUID.randomUUID())));
        }

        @Test
        @DisplayName("동시 요청으로 저장 시 멱등키 유니크 위반이 나면 IDEMPOTENCY_CONFLICT, 예약은 만들지 않는다")
        void concurrentSave() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(referralRepository.saveAndFlush(any(Referral.class)))
                    .willThrow(new DataIntegrityViolationException("duplicate idempotency_key"));

            assertErrorCode(() -> referralService.createReferral(USER_ID, request), ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        // 기존 의뢰와 첫 예약을 조회되게 준비한다
        private Reservation storedWithFirstReservation(Referral existing) {
            Reservation first = reservation(existing, 1);
            given(referralRepository.findByIdempotencyKey(existing.getIdempotencyKey())).willReturn(Optional.of(existing));
            given(reservationRepository.findByReferralIdAndAttemptNo(existing.getId(), 1)).willReturn(Optional.of(first));
            return first;
        }

        private CreateReferralRequest sameRequestAs(Referral existing, UUID slotId) {
            return requestOf(existing.getIdempotencyKey(), existing.getReferringInstitutionId(), existing.getExamTypeId(),
                    existing.getPatientName(), slotId);
        }

        private CreateReferralRequest requestOf(UUID idempotencyKey, UUID referringInstitutionId, UUID examTypeId,
                                                String patientName, UUID slotId) {
            CreateReservationRequest reservation = CreateReservationRequest.builder()
                    .slotId(slotId)
                    .examInstitutionId(UUID.randomUUID())
                    .equipmentId(UUID.randomUUID())
                    .examDate(EXAM_START)
                    .startTime(EXAM_START)
                    .endTime(EXAM_START.plusHours(1))
                    .build();
            return CreateReferralRequest.builder()
                    .referringInstitutionId(referringInstitutionId)
                    .examTypeId(examTypeId)
                    .patientName(patientName)
                    .idempotencyKey(idempotencyKey)
                    .reservation(reservation)
                    .build();
        }

        private void assertConflictWithoutCreating(Runnable action) {
            assertErrorCode(action, ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }
    }

    @Nested
    @DisplayName("getReferral")
    class GetReferral {
        @Test
        @DisplayName("의뢰기관 소속이면 다른 사람이 올린 의뢰도 조회된다")
        void sameInstitution() {
            Referral referral = referralIn(ReferralStatus.PENDING, OTHER_USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertThatCode(() -> referralService.getReferral(referral.getReferringInstitutionId(), referral.getId(), RoleType.REQUESTER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("관리자는 다른 기관의 의뢰도 조회할 수 있다")
        void admin() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertThatCode(() -> referralService.getReferral(UUID.randomUUID(), referral.getId(), RoleType.ADMIN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 기관의 의뢰는 존재를 숨기고 NOT_FOUND로 응답한다")
        void otherInstitution() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.getReferral(UUID.randomUUID(), referral.getId(), RoleType.REQUESTER),
                    ErrorCode.REFERRAL_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 의뢰면 NOT_FOUND")
        void notFound() {
            UUID referralId = UUID.randomUUID();
            given(referralRepository.findById(referralId)).willReturn(Optional.empty());

            assertErrorCode(() -> referralService.getReferral(UUID.randomUUID(), referralId, RoleType.REQUESTER),
                    ErrorCode.REFERRAL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteReferral")
    class DeleteReferral {
        @Test
        @DisplayName("본인의 종료된 의뢰는 soft delete된다")
        void success() {
            Referral referral = referralIn(ReferralStatus.FAILED, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
            updates.register(referral);

            referralService.deleteReferral(USER_ID, referral.getId(), RoleType.REQUESTER);

            assertThat(referral.getDeletedAt()).isNotNull();
            assertThat(referral.getDeletedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("다른 사람의 의뢰는 삭제할 수 없다")
        void otherUser() {
            Referral referral = referralIn(ReferralStatus.FAILED, OTHER_USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
            updates.register(referral);

            assertErrorCode(() -> referralService.deleteReferral(USER_ID, referral.getId(), RoleType.REQUESTER),
                    ErrorCode.REFERRAL_NOT_FOUND);
            assertThat(referral.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("진행 중인 의뢰는 삭제할 수 없다")
        void invalidStatus() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
            updates.register(referral);

            assertErrorCode(() -> referralService.deleteReferral(USER_ID, referral.getId(), RoleType.REQUESTER),
                    ErrorCode.INVALID_REFERRAL_STATUS);
            assertThat(referral.getDeletedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("createRescheduledReservation")
    class CreateRescheduledReservation {
        @Test
        @DisplayName("본인의 재조정 필요 의뢰는 새 예약을 만들고 PENDING으로 돌아간다")
        void success() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = reservationRequest();
            Reservation newReservation = reservation(referral, 2);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(newReservation);
            updates.register(referral);

            ReservationResponse response =
                    referralService.createRescheduledReservation(USER_ID, RoleType.REQUESTER, referral.getId(), request);

            assertThat(response.getId()).isEqualTo(newReservation.getId());
            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(referral.getCurrentReservationId()).isEqualTo(newReservation.getId());
            assertThat(referral.getUpdatedBy()).isEqualTo(USER_ID);

            ArgumentCaptor<UUID> eventIdCaptor = ArgumentCaptor.forClass(UUID.class);
            verify(reservationService).createReservation(eventIdCaptor.capture(), eq(referral), eq(request));

            ArgumentCaptor<ReferralHistory> historyCaptor = ArgumentCaptor.forClass(ReferralHistory.class);
            verify(referralHistoryRepository).save(historyCaptor.capture());
            ReferralHistory history = historyCaptor.getValue();
            assertThat(history.getFromStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
            assertThat(history.getToStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(history.getReason()).isEqualTo(Reason.RESCHEDULED);
            assertThat(history.getEventId()).isEqualTo(eventIdCaptor.getValue());
            assertThat(history.getChangedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("관리자는 다른 사람의 의뢰도 재예약할 수 있다")
        void admin() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, OTHER_USER_ID);
            CreateReservationRequest request = reservationRequest();
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(reservation(referral, 2));
            updates.register(referral);

            referralService.createRescheduledReservation(USER_ID, RoleType.ADMIN, referral.getId(), request);

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);

            // 이력의 변경자는 의뢰 작성자가 아니라 재예약을 요청한 관리자
            ArgumentCaptor<ReferralHistory> historyCaptor = ArgumentCaptor.forClass(ReferralHistory.class);
            verify(referralHistoryRepository).save(historyCaptor.capture());
            assertThat(historyCaptor.getValue().getChangedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("다른 사람의 의뢰는 재예약할 수 없고 예약도 만들지 않는다")
        void otherUser() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, OTHER_USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, RoleType.REQUESTER, referral.getId(), reservationRequest()), ErrorCode.REFERRAL_NOT_FOUND);
            verifyNoInteractions(reservationService);
            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @Test
        @DisplayName("재조정 필요 상태가 아니면 예약을 만들기 전에 실패한다")
        void invalidStatus() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, RoleType.REQUESTER, referral.getId(), reservationRequest()), ErrorCode.INVALID_REFERRAL_STATUS);
            verifyNoInteractions(reservationService);
        }

        @Test
        @DisplayName("새 예약을 만든 뒤 의뢰 UPDATE가 0행이면 예외로 새 예약까지 롤백되게 하고 이력을 남기지 않는다")
        void rescheduleUpdateMissed() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = reservationRequest();
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(reservation(referral, 2));
            // 시뮬레이터에 등록하지 않은 의뢰라 UPDATE가 0행이 된다

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, RoleType.REQUESTER, referral.getId(), request), ErrorCode.INVALID_REFERRAL_STATUS);
            verifyNoInteractions(referralHistoryRepository);
        }

        @Test
        @DisplayName("의뢰가 없으면 REFERRAL_NOT_FOUND")
        void notFound() {
            UUID referralId = UUID.randomUUID();
            given(referralRepository.findByIdForUpdate(referralId)).willReturn(Optional.empty());

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, RoleType.ADMIN, referralId, reservationRequest()), ErrorCode.REFERRAL_NOT_FOUND);
            verifyNoInteractions(reservationService);
        }
    }

    private CreateReferralRequest createRequest(UUID idempotencyKey, CreateReservationRequest reservationRequest) {
        return CreateReferralRequest.builder()
                .referringInstitutionId(UUID.randomUUID())
                .examTypeId(UUID.randomUUID())
                .patientName("홍길동")
                .idempotencyKey(idempotencyKey)
                .reservation(reservationRequest)
                .build();
    }

    private void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
