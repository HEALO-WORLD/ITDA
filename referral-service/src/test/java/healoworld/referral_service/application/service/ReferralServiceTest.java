package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.client.institution.EquipmentOwnerResponse;
import healoworld.referral_service.infrastructure.client.institution.InstitutionClient;
import healoworld.referral_service.infrastructure.client.institution.PartnershipCheckResponse;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotReservationContextResponse;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import healoworld.referral_service.support.ConditionalUpdateStubs;
import healoworld.referral_service.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import feign.FeignException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.EXAM_START;
import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
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

    @Mock
    private InstitutionClient institutionClient;

    // 기본값 Optional.empty(): 검사가 없는 의뢰는 기존처럼 재예약을 받는다
    @Mock
    private ExaminationRepository examinationRepository;

    @Mock
    private SlotClient slotClient;

    @InjectMocks
    private ReferralService referralService;

    // 조건부 UPDATE를 DB처럼 흉내 낸다 (기대 상태가 맞으면 바꾸고 1, 아니면 0)
    private ConditionalUpdateStubs updates;

    // 장비 ID → 소속 기관 ID. institution-service의 장비 소유 정보를 흉내 낸다 (reservationRequest()가 채운다)
    private final Map<UUID, UUID> equipmentOwners = new HashMap<>();

    // 슬롯 ID → 슬롯 정보. slot-service의 슬롯 정보를 흉내 낸다 (createRequest()·rescheduleRequest()가 채운다)
    private final Map<UUID, SlotReservationContextResponse> slots = new HashMap<>();

    @BeforeEach
    void setUp() {
        updates = new ConditionalUpdateStubs(reservationRepository, referralRepository);
        // 이력 기록은 실제 ReferralHistoryService로 위임해 기존처럼 referralHistoryRepository.save로 검증한다
        ReflectionTestUtils.setField(referralService, "referralHistoryService",
                new ReferralHistoryService(referralHistoryRepository, referralRepository));
        // 기본은 협력 관계가 있는 기관. 검증 실패 케이스는 테스트에서 덮어쓴다
        lenient().when(institutionClient.check(any(), any())).thenReturn(new PartnershipCheckResponse(true));
        // 기본은 요청한 장비가 요청한 검사기관 소속. 등록되지 않은 장비는 다른 기관 소속으로 응답한다
        lenient().when(institutionClient.getEquipmentOwner(any())).thenAnswer(invocation -> {
            UUID equipmentId = invocation.getArgument(0);
            UUID owner = equipmentOwners.getOrDefault(equipmentId, UUID.randomUUID());
            return ApiResponse.ok("장비 소속 기관 조회에 성공했습니다.", new EquipmentOwnerResponse(equipmentId, owner));
        });
        // 기본은 요청과 같은 장비·검사 종류·일시의 슬롯. 등록되지 않은 슬롯은 다른 장비의 슬롯으로 응답한다
        lenient().when(slotClient.getReservationContext(any())).thenAnswer(invocation -> {
            UUID slotId = invocation.getArgument(0);
            return slots.getOrDefault(slotId, new SlotReservationContextResponse(slotId, UUID.randomUUID(), UUID.randomUUID(),
                    EXAM_START.toLocalDate(), EXAM_START.toLocalTime(), EXAM_START.plusHours(1).toLocalTime()));
        });
    }

    // 헤더의 소속 기관이 본문의 의뢰기관과 같은 정상 요청으로 생성한다
    private ReferralResponse create(UUID userId, CreateReferralRequest request) {
        return referralService.createReferral(userId, request.getReferringInstitutionId(), request);
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

            create(USER_ID, request);

            verify(institutionClient).check(request.getReferringInstitutionId(), reservationRequest.getExamInstitutionId());
            verify(institutionClient).getEquipmentOwner(reservationRequest.getEquipmentId());
            verify(slotClient).getReservationContext(reservationRequest.getSlotId());
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

            assertThat(create(USER_ID, sameRequestAs(existing, first.getSlotId())).getId())
                    .isEqualTo(existing.getId());

            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository, institutionClient, slotClient);
        }

        @Test
        @DisplayName("헤더의 소속 기관과 본문의 의뢰기관이 다르면 FORBIDDEN, 협력 관계 확인도 저장도 하지 않는다")
        void otherInstitution() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());

            assertErrorCode(() -> referralService.createReferral(USER_ID, UUID.randomUUID(), request), ErrorCode.REFERRAL_FORBIDDEN);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(institutionClient, reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("검사기관과 협력 관계가 아니면 PARTNERSHIP_NOT_ACTIVE(403), 의뢰·예약을 만들지 않는다")
        void notPartner() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(institutionClient.check(any(), any())).willReturn(new PartnershipCheckResponse(false));

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.PARTNERSHIP_NOT_ACTIVE);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("장비가 요청한 검사기관 소속이 아니면 EQUIPMENT_NOT_IN_EXAM_INSTITUTION(400), 의뢰·예약을 만들지 않는다")
        void equipmentOfOtherInstitution() {
            CreateReservationRequest reservationRequest = reservationRequest();
            equipmentOwners.put(reservationRequest.getEquipmentId(), UUID.randomUUID());
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest);
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("장비가 없거나 삭제됐으면(404) EQUIPMENT_NOT_IN_EXAM_INSTITUTION(400), 의뢰·예약을 만들지 않는다")
        void equipmentNotFound() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(institutionClient.getEquipmentOwner(any())).willThrow(FeignException.NotFound.class);

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("장비 소유 기관 조회가 404 외의 이유로 실패하면 503(INSTITUTION_SERVICE_UNAVAILABLE), 의뢰·예약을 만들지 않는다")
        void equipmentOwnerLookupFails() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(institutionClient.getEquipmentOwner(any())).willThrow(FeignException.class);

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.INSTITUTION_SERVICE_UNAVAILABLE);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("협력 관계가 아니면 장비 소유 기관은 조회하지 않는다")
        void notPartnerSkipsEquipmentCheck() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(institutionClient.check(any(), any())).willReturn(new PartnershipCheckResponse(false));

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.PARTNERSHIP_NOT_ACTIVE);
            verify(institutionClient, never()).getEquipmentOwner(any());
        }

        @ParameterizedTest(name = "{0}가 다르면 SLOT_MISMATCH")
        @ValueSource(strings = {"equipment", "examType", "date", "startTime", "endTime"})
        @DisplayName("슬롯의 장비·검사 종류·일시가 요청과 다르면 SLOT_MISMATCH(400), 의뢰·예약을 만들지 않는다")
        void slotMismatch(String field) {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            SlotReservationContextResponse slot = slots.get(request.getReservation().getSlotId());
            slots.put(slot.slotId(), differentIn(slot, field));
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.SLOT_MISMATCH);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("요청의 검사 일자와 시작 시각의 날짜가 다르면 SLOT_MISMATCH(400)")
        void examDateDiffersFromStartTimeDate() {
            CreateReservationRequest reservationRequest = reservationRequestBuilder()
                    .examDate(EXAM_START)
                    .startTime(EXAM_START.plusDays(1))
                    .endTime(EXAM_START.plusDays(1).plusHours(1))
                    .build();
            equipmentOwners.put(reservationRequest.getEquipmentId(), reservationRequest.getExamInstitutionId());
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest);
            // 슬롯은 검사 일자(examDate) 기준으로 날짜·시간이 맞는 상태
            SlotReservationContextResponse slot = slots.get(reservationRequest.getSlotId());
            slots.put(slot.slotId(), new SlotReservationContextResponse(slot.slotId(), slot.equipmentId(), slot.examTypeId(),
                    EXAM_START.toLocalDate(), slot.startTime(), slot.endTime()));
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.SLOT_MISMATCH);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("슬롯이 없거나 삭제됐으면(404) SLOT_MISMATCH(400), 의뢰·예약을 만들지 않는다")
        void slotNotFound() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(slotClient.getReservationContext(any())).willThrow(FeignException.NotFound.class);

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.SLOT_MISMATCH);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("슬롯 조회가 404 외의 이유로 실패하면 503(SLOT_SERVICE_UNAVAILABLE), 의뢰·예약을 만들지 않는다")
        void slotLookupFails() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(slotClient.getReservationContext(any())).willThrow(FeignException.class);

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.SLOT_SERVICE_UNAVAILABLE);
            verify(referralRepository, never()).saveAndFlush(any());
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("장비 소유 기관 검증에 실패하면 슬롯은 조회하지 않는다")
        void equipmentCheckFailureSkipsSlotCheck() {
            CreateReservationRequest reservationRequest = reservationRequest();
            equipmentOwners.put(reservationRequest.getEquipmentId(), UUID.randomUUID());
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest);
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
            verifyNoInteractions(slotClient);
        }

        @Test
        @DisplayName("institution-service 호출이 실패하면 503(INSTITUTION_SERVICE_UNAVAILABLE), 의뢰·예약을 만들지 않는다")
        void institutionServiceDown() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(institutionClient.check(any(), any())).willThrow(FeignException.class);

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.INSTITUTION_SERVICE_UNAVAILABLE);
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

            assertConflictWithoutCreating(() -> create(USER_ID, request));
        }

        @Test
        @DisplayName("같은 멱등키인데 검사 종류가 다르면 IDEMPOTENCY_CONFLICT")
        void differentExamType() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            Reservation first = storedWithFirstReservation(existing);
            CreateReferralRequest request = requestOf(existing.getIdempotencyKey(), existing.getReferringInstitutionId(),
                    UUID.randomUUID(), existing.getPatientName(), first.getSlotId());

            assertConflictWithoutCreating(() -> create(USER_ID, request));
        }

        @Test
        @DisplayName("같은 멱등키인데 첫 예약의 슬롯과 다르면 IDEMPOTENCY_CONFLICT")
        void differentSlot() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            storedWithFirstReservation(existing);

            assertConflictWithoutCreating(() -> create(USER_ID, sameRequestAs(existing, UUID.randomUUID())));
        }

        @Test
        @DisplayName("같은 멱등키를 다른 사용자가 쓰면 기존 의뢰를 넘겨주지 않고 IDEMPOTENCY_CONFLICT")
        void otherUser() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            Reservation first = storedWithFirstReservation(existing);

            assertConflictWithoutCreating(() -> create(OTHER_USER_ID, sameRequestAs(existing, first.getSlotId())));
        }

        @Test
        @DisplayName("의뢰는 있는데 첫 예약을 찾을 수 없으면 IDEMPOTENCY_CONFLICT")
        void firstReservationMissing() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findByIdempotencyKey(existing.getIdempotencyKey())).willReturn(Optional.of(existing));
            given(reservationRepository.findByReferralIdAndAttemptNo(existing.getId(), 1)).willReturn(Optional.empty());

            assertConflictWithoutCreating(() -> create(USER_ID, sameRequestAs(existing, UUID.randomUUID())));
        }

        @Test
        @DisplayName("동시 요청으로 저장 시 멱등키 유니크 위반이 나면 IDEMPOTENCY_CONFLICT, 예약은 만들지 않는다")
        void concurrentSave() {
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest());
            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(referralRepository.saveAndFlush(any(Referral.class)))
                    .willThrow(new DataIntegrityViolationException("duplicate idempotency_key"));

            assertErrorCode(() -> create(USER_ID, request), ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
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

            assertThatCode(() -> referralService.getReferral(referral.getReferringInstitutionId(), referral.getId(), Role.REQUESTER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("관리자는 다른 기관의 의뢰도 조회할 수 있다")
        void admin() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertThatCode(() -> referralService.getReferral(UUID.randomUUID(), referral.getId(), Role.ADMIN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 기관의 의뢰는 존재를 숨기고 NOT_FOUND로 응답한다")
        void otherInstitution() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.getReferral(UUID.randomUUID(), referral.getId(), Role.REQUESTER),
                    ErrorCode.REFERRAL_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 의뢰면 NOT_FOUND")
        void notFound() {
            UUID referralId = UUID.randomUUID();
            given(referralRepository.findById(referralId)).willReturn(Optional.empty());

            assertErrorCode(() -> referralService.getReferral(UUID.randomUUID(), referralId, Role.REQUESTER),
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

            referralService.deleteReferral(USER_ID, referral.getId(), Role.REQUESTER);

            assertThat(referral.getDeletedAt()).isNotNull();
            assertThat(referral.getDeletedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("다른 사람의 의뢰는 삭제할 수 없다")
        void otherUser() {
            Referral referral = referralIn(ReferralStatus.FAILED, OTHER_USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
            updates.register(referral);

            assertErrorCode(() -> referralService.deleteReferral(USER_ID, referral.getId(), Role.REQUESTER),
                    ErrorCode.REFERRAL_NOT_FOUND);
            assertThat(referral.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("진행 중인 의뢰는 삭제할 수 없다")
        void invalidStatus() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
            updates.register(referral);

            assertErrorCode(() -> referralService.deleteReferral(USER_ID, referral.getId(), Role.REQUESTER),
                    ErrorCode.INVALID_REFERRAL_STATUS);
            assertThat(referral.getDeletedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("createRescheduledReservation")
    class CreateRescheduledReservation {

        @ParameterizedTest
        @EnumSource(value = ExaminationStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
        @DisplayName("검사가 시작·완료된 의뢰는 409이고 새 예약·Outbox·이력을 만들지 않는다")
        void examinationAlreadyStarted(ExaminationStatus status) {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(examinationRepository.findByReferralId(referral.getId())).willReturn(Optional.of(examination(referral, status)));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), reservationRequest()),
                    ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
            // 새 예약과 BookSlot·HoldRequested Outbox는 reservationService.createReservation()에서 만들어진다
            verifyNoInteractions(reservationService, institutionClient, referralHistoryRepository);
            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @Test
        @DisplayName("SCHEDULED 검사가 있는 의뢰는 재예약을 받는다")
        void scheduledExaminationAllowsReschedule() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = rescheduleRequest(referral);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(examinationRepository.findByReferralId(referral.getId()))
                    .willReturn(Optional.of(examination(referral, ExaminationStatus.SCHEDULED)));
            given(institutionClient.check(referral.getReferringInstitutionId(), request.getExamInstitutionId()))
                    .willReturn(new PartnershipCheckResponse(true));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(reservation(referral, 2));
            updates.register(referral);

            referralService.createRescheduledReservation(USER_ID, Role.REQUESTER, referral.getId(), request);

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
        }

        private Examination examination(Referral referral, ExaminationStatus status) {
            Examination examination = Examination.schedule(referral.getId(), UUID.randomUUID(), UUID.randomUUID());
            ReflectionTestUtils.setField(examination, "status", status);
            return examination;
        }
        @Test
        @DisplayName("본인의 재조정 필요 의뢰는 새 예약을 만들고 PENDING으로 돌아간다")
        void success() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = rescheduleRequest(referral);
            Reservation newReservation = reservation(referral, 2);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(newReservation);
            updates.register(referral);

            ReservationResponse response =
                    referralService.createRescheduledReservation(USER_ID, Role.REQUESTER, referral.getId(), request);

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
            CreateReservationRequest request = rescheduleRequest(referral);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(reservation(referral, 2));
            updates.register(referral);

            referralService.createRescheduledReservation(USER_ID, Role.ADMIN, referral.getId(), request);

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
                    USER_ID, Role.REQUESTER, referral.getId(), reservationRequest()), ErrorCode.REFERRAL_NOT_FOUND);
            verifyNoInteractions(reservationService);
            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @Test
        @DisplayName("재조정 필요 상태가 아니면 예약을 만들기 전에 실패한다")
        void invalidStatus() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), reservationRequest()), ErrorCode.INVALID_REFERRAL_STATUS);
            verifyNoInteractions(reservationService);
        }

        @Test
        @DisplayName("새 검사기관과 협력 관계가 아니면 PARTNERSHIP_NOT_ACTIVE(403), 예약을 만들지 않는다")
        void notPartner() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = reservationRequest();
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(institutionClient.check(referral.getReferringInstitutionId(), request.getExamInstitutionId()))
                    .willReturn(new PartnershipCheckResponse(false));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), request), ErrorCode.PARTNERSHIP_NOT_ACTIVE);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("새 장비가 새 검사기관 소속이 아니면 EQUIPMENT_NOT_IN_EXAM_INSTITUTION(400), 예약을 만들지 않는다")
        void equipmentOfOtherInstitution() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = reservationRequest();
            equipmentOwners.put(request.getEquipmentId(), UUID.randomUUID());
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), request), ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
            verifyNoInteractions(reservationService, referralHistoryRepository);
            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @Test
        @DisplayName("새 장비가 없거나 삭제됐으면(404) EQUIPMENT_NOT_IN_EXAM_INSTITUTION(400), 예약을 만들지 않는다")
        void equipmentNotFound() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(institutionClient.getEquipmentOwner(any())).willThrow(FeignException.NotFound.class);

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), reservationRequest()), ErrorCode.EQUIPMENT_NOT_IN_EXAM_INSTITUTION);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("장비 소유 기관 조회가 404 외의 이유로 실패하면 503(INSTITUTION_SERVICE_UNAVAILABLE), 예약을 만들지 않는다")
        void equipmentOwnerLookupFails() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(institutionClient.getEquipmentOwner(any())).willThrow(FeignException.class);

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), reservationRequest()), ErrorCode.INSTITUTION_SERVICE_UNAVAILABLE);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("새 슬롯의 검사 종류가 의뢰의 검사 종류와 다르면 SLOT_MISMATCH(400), 예약을 만들지 않는다")
        void slotOfOtherExamType() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = reservationRequest();
            registerSlot(request, UUID.randomUUID());
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), request), ErrorCode.SLOT_MISMATCH);
            verifyNoInteractions(reservationService, referralHistoryRepository);
            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @Test
        @DisplayName("새 슬롯이 없거나 삭제됐으면(404) SLOT_MISMATCH(400), 예약을 만들지 않는다")
        void slotNotFound() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(slotClient.getReservationContext(any())).willThrow(FeignException.NotFound.class);

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), rescheduleRequest(referral)), ErrorCode.SLOT_MISMATCH);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("슬롯 조회가 404 외의 이유로 실패하면 503(SLOT_SERVICE_UNAVAILABLE), 예약을 만들지 않는다")
        void slotLookupFails() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(slotClient.getReservationContext(any())).willThrow(FeignException.class);

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), rescheduleRequest(referral)), ErrorCode.SLOT_SERVICE_UNAVAILABLE);
            verifyNoInteractions(reservationService, referralHistoryRepository);
        }

        @Test
        @DisplayName("새 예약을 만든 뒤 의뢰 UPDATE가 0행이면 예외로 새 예약까지 롤백되게 하고 이력을 남기지 않는다")
        void rescheduleUpdateMissed() {
            Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, USER_ID);
            CreateReservationRequest request = rescheduleRequest(referral);
            given(referralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
            given(reservationService.createReservation(any(UUID.class), eq(referral), eq(request))).willReturn(reservation(referral, 2));
            // 시뮬레이터에 등록하지 않은 의뢰라 UPDATE가 0행이 된다

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.REQUESTER, referral.getId(), request), ErrorCode.INVALID_REFERRAL_STATUS);
            verifyNoInteractions(referralHistoryRepository);
        }

        @Test
        @DisplayName("의뢰가 없으면 REFERRAL_NOT_FOUND")
        void notFound() {
            UUID referralId = UUID.randomUUID();
            given(referralRepository.findByIdForUpdate(referralId)).willReturn(Optional.empty());

            assertErrorCode(() -> referralService.createRescheduledReservation(
                    USER_ID, Role.ADMIN, referralId, reservationRequest()), ErrorCode.REFERRAL_NOT_FOUND);
            verifyNoInteractions(reservationService);
        }
    }

    // 픽스처 예약 요청을 만들고, 요청한 장비를 요청한 검사기관 소속으로 등록한다
    private CreateReservationRequest reservationRequest() {
        CreateReservationRequest request = DomainFixtures.reservationRequest();
        equipmentOwners.put(request.getEquipmentId(), request.getExamInstitutionId());
        return request;
    }

    // 픽스처와 같은 값의 예약 요청 빌더 (일부 값만 바꿔서 쓸 때)
    private CreateReservationRequest.CreateReservationRequestBuilder reservationRequestBuilder() {
        return CreateReservationRequest.builder()
                .slotId(UUID.randomUUID())
                .examInstitutionId(UUID.randomUUID())
                .equipmentId(UUID.randomUUID())
                .examDate(EXAM_START)
                .startTime(EXAM_START)
                .endTime(EXAM_START.plusHours(1));
    }

    // 재예약 요청을 만들고, 의뢰의 검사 종류로 요청과 같은 슬롯을 등록한다
    private CreateReservationRequest rescheduleRequest(Referral referral) {
        CreateReservationRequest request = reservationRequest();
        registerSlot(request, referral.getExamTypeId());
        return request;
    }

    // 요청과 같은 장비·검사 종류·일시의 슬롯을 등록한다
    private void registerSlot(CreateReservationRequest request, UUID examTypeId) {
        slots.put(request.getSlotId(), new SlotReservationContextResponse(request.getSlotId(), request.getEquipmentId(), examTypeId,
                request.getExamDate().toLocalDate(), request.getStartTime().toLocalTime(), request.getEndTime().toLocalTime()));
    }

    // 슬롯 정보 중 한 항목만 다르게 바꾼다
    private SlotReservationContextResponse differentIn(SlotReservationContextResponse slot, String field) {
        return new SlotReservationContextResponse(
                slot.slotId(),
                field.equals("equipment") ? UUID.randomUUID() : slot.equipmentId(),
                field.equals("examType") ? UUID.randomUUID() : slot.examTypeId(),
                field.equals("date") ? slot.date().plusDays(1) : slot.date(),
                field.equals("startTime") ? slot.startTime().plusMinutes(30) : slot.startTime(),
                field.equals("endTime") ? slot.endTime().plusMinutes(30) : slot.endTime());
    }

    // 의뢰 생성 요청을 만들고, 요청과 같은 슬롯을 등록한다
    private CreateReferralRequest createRequest(UUID idempotencyKey, CreateReservationRequest reservationRequest) {
        UUID examTypeId = UUID.randomUUID();
        registerSlot(reservationRequest, examTypeId);
        return CreateReferralRequest.builder()
                .referringInstitutionId(UUID.randomUUID())
                .examTypeId(examTypeId)
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
