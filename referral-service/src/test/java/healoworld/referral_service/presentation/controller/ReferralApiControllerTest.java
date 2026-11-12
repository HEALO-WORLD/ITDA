package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReferralHistoryResponse;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReferralController 웹 계층 테스트 (결과 조회 API는 ReferralControllerTest, 헤더 위조 방지는 AuthHeaderContractTest).
 * <p>
 * 의뢰 생성·재예약·단건·목록·이력·삭제 API의 요청 바인딩, 검증 메시지, 헤더 누락, 업무 예외의 상태 코드 변환,
 * 응답 형식(ApiResponse·PagedResponse)을 확인한다. Service는 Mock이다.
 */
@WebMvcTest(ReferralController.class)
@ActiveProfiles("test")
class ReferralApiControllerTest {

    private static final String BASE = "/api/v1/referrals";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReferralService referralService;

    @MockitoBean
    private ReferralHistoryService referralHistoryService;

    @MockitoBean
    private ExaminationResultService examinationResultService;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID userId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();
    private final UUID referralId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID idempotencyKey = UUID.randomUUID();
    private final UUID examTypeId = UUID.randomUUID();
    private final UUID slotId = UUID.randomUUID();
    private final UUID examInstitutionId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();

    // ==============================
    // POST /api/v1/referrals (의뢰 생성)
    // ==============================

    @Nested
    @DisplayName("의뢰 생성 POST /api/v1/referrals")
    class CreateReferral {

        @Test
        @DisplayName("정상 요청은 201과 생성된 의뢰를 돌려주고, 헤더의 사용자·기관과 본문 값을 Service에 그대로 넘긴다")
        void created() throws Exception {
            given(referralService.createReferral(eq(userId), eq(institutionId), any(CreateReferralRequest.class)))
                    .willReturn(referral(ReferralStatus.PENDING));

            mockMvc.perform(createRequest(referralBody(reservationJson())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("의뢰 생성, 예약 접수가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.id").value(referralId.toString()))
                    .andExpect(jsonPath("$.data.referralStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data.currentReservationId").value(reservationId.toString()))
                    .andExpect(jsonPath("$.timestamp").exists());

            ArgumentCaptor<CreateReferralRequest> captor = ArgumentCaptor.forClass(CreateReferralRequest.class);
            then(referralService).should().createReferral(eq(userId), eq(institutionId), captor.capture());
            CreateReferralRequest sent = captor.getValue();
            assertThat(sent.getReferringInstitutionId()).isEqualTo(institutionId);
            assertThat(sent.getExamTypeId()).isEqualTo(examTypeId);
            assertThat(sent.getPatientName()).isEqualTo("홍길동");
            assertThat(sent.getIdempotencyKey()).isEqualTo(idempotencyKey);
            CreateReservationRequest reservation = sent.getReservation();
            assertThat(reservation.getSlotId()).isEqualTo(slotId);
            assertThat(reservation.getExamInstitutionId()).isEqualTo(examInstitutionId);
            assertThat(reservation.getEquipmentId()).isEqualTo(equipmentId);
            assertThat(reservation.getStartTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 10, 0));
            assertThat(reservation.getEndTime()).isEqualTo(LocalDateTime.of(2026, 12, 1, 11, 0));
        }

        @ParameterizedTest(name = "{0} 누락 → \"{1}\"")
        @CsvSource({
                "referringInstitutionId, 의뢰 기관 ID는 필수입니다.",
                "examTypeId, 검사 종류는 필수입니다.",
                "patientName, 검사자 이름은 필수입니다.",
                "idempotencyKey, 멱등 키는 필수입니다."
        })
        @DisplayName("필수 필드가 없으면 400 VALIDATION_FAILED와 DTO의 안내 메시지이고 Service를 호출하지 않는다")
        void missingRequiredField(String field, String message) throws Exception {
            mockMvc.perform(createRequest(referralBodyWithout(field)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(message));

            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("환자 이름이 공백뿐이면 400 VALIDATION_FAILED")
        void blankPatientName() throws Exception {
            String body = referralBody(reservationJson()).replace("\"홍길동\"", "\"   \"");

            mockMvc.perform(createRequest(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("검사자 이름은 필수입니다."));

            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("첫 예약(reservation)이 없으면 400 VALIDATION_FAILED")
        void missingReservation() throws Exception {
            mockMvc.perform(createRequest(referralBodyWithout("reservation")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(referralService);
        }

        @ParameterizedTest(name = "reservation.{0} 누락 → \"{1}\"")
        @CsvSource({
                "slotId, 슬롯 ID는 필수입니다.",
                "examInstitutionId, 검사 기관 ID는 필수입니다.",
                "equipmentId, 장비 ID는 필수입니다.",
                "examDate, 검사 일자는 필수입니다.",
                "startTime, 검사 시작 시간은 필수입니다.",
                "endTime, 검사 종료 시간은 필수입니다."
        })
        @DisplayName("첫 예약의 필수 필드가 없으면(@Valid 중첩 검증) 400 VALIDATION_FAILED와 안내 메시지")
        void missingReservationField(String field, String message) throws Exception {
            mockMvc.perform(createRequest(referralBody(reservationJsonWithout(field))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value(message));

            verifyNoInteractions(referralService);
        }

        @ParameterizedTest(name = "{0} 헤더 누락")
        @CsvSource({"X-User-Id", "X-Institution-Id"})
        @DisplayName("요청자 헤더가 없으면 400 MISSING_HEADER이고 메시지에 헤더 이름이 담긴다")
        void missingHeader(String header) throws Exception {
            MockHttpServletRequestBuilder request = post(BASE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(referralBody(reservationJson()));
            if (!header.equals("X-User-Id")) {
                request.header("X-User-Id", userId.toString());
            }
            if (!header.equals("X-Institution-Id")) {
                request.header("X-Institution-Id", institutionId.toString());
            }

            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(header)));

            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("JSON 형식이 깨졌으면 400 INVALID_INPUT")
        void malformedJson() throws Exception {
            mockMvc.perform(createRequest("{\"patientName\": \"홍길동\""))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("멱등 키가 UUID 형식이 아니면 400 INVALID_INPUT")
        void idempotencyKeyNotUuid() throws Exception {
            String body = referralBody(reservationJson()).replace(idempotencyKey.toString(), "not-a-uuid");

            mockMvc.perform(createRequest(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(referralService);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ErrorCode.class, names = {
                "REFERRAL_FORBIDDEN", "PARTNERSHIP_NOT_ACTIVE", "EQUIPMENT_NOT_IN_EXAM_INSTITUTION",
                "REFERRAL_IDEMPOTENCY_CONFLICT", "INSTITUTION_SERVICE_UNAVAILABLE"})
        @DisplayName("Service의 업무 예외는 ErrorCode의 상태 코드·코드·메시지로 응답한다")
        void businessErrors(ErrorCode errorCode) throws Exception {
            given(referralService.createReferral(any(), any(), any())).willThrow(new BaseException(errorCode));

            mockMvc.perform(createRequest(referralBody(reservationJson())))
                    .andExpect(status().is(errorCode.getStatus().value()))
                    .andExpect(jsonPath("$.code").value(errorCode.name()))
                    .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
        }

        private MockHttpServletRequestBuilder createRequest(String body) {
            return post(BASE)
                    .header("X-User-Id", userId.toString())
                    .header("X-Institution-Id", institutionId.toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
        }
    }

    // ==============================
    // POST /api/v1/referrals/{referralId}/reschedule (재예약)
    // ==============================

    @Nested
    @DisplayName("재예약 POST /api/v1/referrals/{referralId}/reschedule")
    class Reschedule {

        @Test
        @DisplayName("정상 요청은 201과 새 예약을 돌려주고, 사용자·역할·의뢰 ID·본문을 Service에 넘긴다")
        void created() throws Exception {
            given(referralService.createRescheduledReservation(eq(userId), eq(Role.REQUESTER), eq(referralId),
                    any(CreateReservationRequest.class))).willReturn(reservation(2, ReservationStatus.REQUESTED));

            mockMvc.perform(rescheduleRequest(reservationJson()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("재 예약 접수가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.id").value(reservationId.toString()))
                    .andExpect(jsonPath("$.data.referralId").value(referralId.toString()))
                    .andExpect(jsonPath("$.data.attemptNo").value(2))
                    .andExpect(jsonPath("$.data.reservationStatus").value("REQUESTED"));

            ArgumentCaptor<CreateReservationRequest> captor = ArgumentCaptor.forClass(CreateReservationRequest.class);
            then(referralService).should()
                    .createRescheduledReservation(eq(userId), eq(Role.REQUESTER), eq(referralId), captor.capture());
            assertThat(captor.getValue().getSlotId()).isEqualTo(slotId);
            assertThat(captor.getValue().getEquipmentId()).isEqualTo(equipmentId);
        }

        @Test
        @DisplayName("새 예약의 필수 필드가 없으면 400 VALIDATION_FAILED이고 Service를 호출하지 않는다")
        void missingField() throws Exception {
            mockMvc.perform(rescheduleRequest(reservationJsonWithout("slotId")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.message").value("슬롯 ID는 필수입니다."));

            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("X-Role이 없으면 400 MISSING_HEADER")
        void missingRole() throws Exception {
            mockMvc.perform(post(BASE + "/{referralId}/reschedule", referralId)
                            .header("X-User-Id", userId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(reservationJson()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

            verifyNoInteractions(referralService);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ErrorCode.class, names = {
                "REFERRAL_NOT_FOUND", "INVALID_REFERRAL_STATUS", "EXAMINATION_RESERVATION_NOT_CHANGEABLE",
                "PARTNERSHIP_NOT_ACTIVE", "INSTITUTION_SERVICE_UNAVAILABLE"})
        @DisplayName("Service의 업무 예외는 ErrorCode의 상태 코드·코드로 응답한다")
        void businessErrors(ErrorCode errorCode) throws Exception {
            given(referralService.createRescheduledReservation(any(), any(), any(), any()))
                    .willThrow(new BaseException(errorCode));

            mockMvc.perform(rescheduleRequest(reservationJson()))
                    .andExpect(status().is(errorCode.getStatus().value()))
                    .andExpect(jsonPath("$.code").value(errorCode.name()));
        }

        private MockHttpServletRequestBuilder rescheduleRequest(String body) {
            return post(BASE + "/{referralId}/reschedule", referralId)
                    .header("X-User-Id", userId.toString())
                    .header("X-Role", "REQUESTER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body);
        }
    }

    // ==============================
    // GET /api/v1/referrals/{referralId} (단건)
    // ==============================

    @Nested
    @DisplayName("의뢰 단건 조회 GET /api/v1/referrals/{referralId}")
    class GetReferral {

        @Test
        @DisplayName("200과 의뢰 필드를 돌려주고, 기관·의뢰 ID·역할을 Service에 넘긴다")
        void ok() throws Exception {
            given(referralService.getReferral(institutionId, referralId, Role.REQUESTER))
                    .willReturn(referral(ReferralStatus.CONFIRMED));

            mockMvc.perform(get(BASE + "/{referralId}", referralId)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("의뢰 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.id").value(referralId.toString()))
                    .andExpect(jsonPath("$.data.referringInstitutionId").value(institutionId.toString()))
                    .andExpect(jsonPath("$.data.referrerId").value(userId.toString()))
                    .andExpect(jsonPath("$.data.examTypeId").value(examTypeId.toString()))
                    .andExpect(jsonPath("$.data.patientName").value("홍길동"))
                    .andExpect(jsonPath("$.data.referralStatus").value("CONFIRMED"));
        }

        @Test
        @DisplayName("없거나 다른 기관의 의뢰면 404 REFERRAL_NOT_FOUND")
        void notFound() throws Exception {
            given(referralService.getReferral(any(), any(), any()))
                    .willThrow(new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

            mockMvc.perform(get(BASE + "/{referralId}", referralId)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REFERRAL_NOT_FOUND"));
        }

        @Test
        @DisplayName("의뢰 ID가 UUID 형식이 아니면 400 INVALID_INPUT")
        void invalidId() throws Exception {
            mockMvc.perform(get(BASE + "/{referralId}", "not-a-uuid")
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(referralService);
        }
    }

    // ==============================
    // GET /api/v1/referrals (목록)
    // ==============================

    @Nested
    @DisplayName("의뢰 목록 조회 GET /api/v1/referrals")
    class GetReferrals {

        @Test
        @DisplayName("쿼리 파라미터가 검색 조건으로 바인딩되고, PagedResponse 형식으로 200을 돌려준다")
        void bindsConditionAndPages() throws Exception {
            UUID referrerId = UUID.randomUUID();
            given(referralService.getReferrals(eq(institutionId), any(ReferralSearchCondition.class), eq(Role.REQUESTER)))
                    .willReturn(new PagedResponse<>(List.of(referral(ReferralStatus.FAILED)), 31, 2, 30, 1));

            mockMvc.perform(get(BASE)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER")
                            .param("status", "FAILED")
                            .param("referrerId", referrerId.toString())
                            .param("examTypeId", examTypeId.toString())
                            .param("patientName", "홍")
                            .param("page", "1")
                            .param("size", "30")
                            .param("sort", "updatedAt,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("의뢰 목록 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.contents[0].id").value(referralId.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(31))
                    .andExpect(jsonPath("$.data.totalPages").value(2))
                    .andExpect(jsonPath("$.data.size").value(30))
                    .andExpect(jsonPath("$.data.number").value(1));

            ReferralSearchCondition cond = capturedCondition();
            assertThat(cond.status()).isEqualTo(ReferralStatus.FAILED);
            assertThat(cond.referrerId()).isEqualTo(referrerId);
            assertThat(cond.examTypeId()).isEqualTo(examTypeId);
            assertThat(cond.patientName()).isEqualTo("홍");
            assertThat(cond.toPageable().getPageNumber()).isEqualTo(1);
            assertThat(cond.toPageable().getPageSize()).isEqualTo(30);
            assertThat(cond.toPageable().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "updatedAt"));
        }

        @Test
        @DisplayName("파라미터가 없으면 page 0, size 10, createdAt 내림차순이다")
        void defaults() throws Exception {
            given(referralService.getReferrals(any(), any(), any())).willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));

            mockMvc.perform(get(BASE)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contents").isEmpty());

            ReferralSearchCondition cond = capturedCondition();
            assertThat(cond.status()).isNull();
            assertThat(cond.toPageable().getPageNumber()).isZero();
            assertThat(cond.toPageable().getPageSize()).isEqualTo(10);
            assertThat(cond.toPageable().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        @Test
        @DisplayName("허용되지 않은 size·정렬 필드·음수 page는 기본값으로 바뀐다 (400이 아니다)")
        void invalidPagingFallsBack() throws Exception {
            given(referralService.getReferrals(any(), any(), any())).willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));

            mockMvc.perform(get(BASE)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER")
                            .param("page", "-1")
                            .param("size", "999")
                            .param("sort", "patientName,asc"))
                    .andExpect(status().isOk());

            ReferralSearchCondition cond = capturedCondition();
            assertThat(cond.toPageable().getPageNumber()).isZero();
            assertThat(cond.toPageable().getPageSize()).isEqualTo(10);
            assertThat(cond.toPageable().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        @Test
        @DisplayName("정의되지 않은 의뢰 상태로 필터하면 400 VALIDATION_FAILED이고 Service를 호출하지 않는다")
        void invalidStatus() throws Exception {
            mockMvc.perform(get(BASE)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER")
                            .param("status", "UNKNOWN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(referralService);
        }

        private ReferralSearchCondition capturedCondition() {
            ArgumentCaptor<ReferralSearchCondition> captor = ArgumentCaptor.forClass(ReferralSearchCondition.class);
            then(referralService).should().getReferrals(eq(institutionId), captor.capture(), eq(Role.REQUESTER));
            return captor.getValue();
        }
    }

    // ==============================
    // GET /api/v1/referrals/{referralId}/histories (이력)
    // ==============================

    @Nested
    @DisplayName("의뢰 변경 내역 GET /api/v1/referrals/{referralId}/histories")
    class Histories {

        @Test
        @DisplayName("200과 Service가 돌려준 순서 그대로 이력 목록을 돌려준다")
        void ok() throws Exception {
            UUID createdEvent = UUID.randomUUID();
            UUID confirmedEvent = UUID.randomUUID();
            given(referralHistoryService.getReferralHistories(institutionId, referralId, Role.REQUESTER))
                    .willReturn(List.of(
                            history(createdEvent, null, ReferralStatus.PENDING, Reason.REFERRAL_CREATED, userId),
                            history(confirmedEvent, ReferralStatus.PENDING, ReferralStatus.CONFIRMED,
                                    Reason.RESERVATION_CONFIRMED, null)));

            mockMvc.perform(get(BASE + "/{referralId}/histories", referralId)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("의뢰 변경 목록 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].reason").value("REFERRAL_CREATED"))
                    .andExpect(jsonPath("$.data[0].fromStatus").doesNotExist())
                    .andExpect(jsonPath("$.data[0].toStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data[0].eventId").value(createdEvent.toString()))
                    .andExpect(jsonPath("$.data[0].changedBy").value(userId.toString()))
                    .andExpect(jsonPath("$.data[1].reason").value("RESERVATION_CONFIRMED"))
                    .andExpect(jsonPath("$.data[1].fromStatus").value("PENDING"))
                    .andExpect(jsonPath("$.data[1].toStatus").value("CONFIRMED"))
                    .andExpect(jsonPath("$.data[1].reservationId").value(reservationId.toString()));
        }

        @Test
        @DisplayName("없거나 다른 기관의 의뢰면 404 REFERRAL_NOT_FOUND")
        void notFound() throws Exception {
            given(referralHistoryService.getReferralHistories(any(), any(), any()))
                    .willThrow(new BaseException(ErrorCode.REFERRAL_NOT_FOUND));

            mockMvc.perform(get(BASE + "/{referralId}/histories", referralId)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("REFERRAL_NOT_FOUND"));
        }
    }

    // ==============================
    // DELETE /api/v1/referrals/{referralId} (삭제)
    // ==============================

    @Nested
    @DisplayName("의뢰 삭제 DELETE /api/v1/referrals/{referralId}")
    class Delete {

        @Test
        @DisplayName("200과 data null을 돌려주고, 사용자·의뢰 ID·역할을 Service에 넘긴다")
        void ok() throws Exception {
            mockMvc.perform(delete(BASE + "/{referralId}", referralId)
                            .header("X-User-Id", userId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("삭제가 완료되었습니다."))
                    .andExpect(jsonPath("$.data").doesNotExist());

            then(referralService).should().deleteReferral(userId, referralId, Role.REQUESTER);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ErrorCode.class, names = {"REFERRAL_NOT_FOUND", "INVALID_REFERRAL_STATUS"})
        @DisplayName("없는 의뢰는 404, 삭제할 수 없는 상태는 409")
        void businessErrors(ErrorCode errorCode) throws Exception {
            org.mockito.BDDMockito.willThrow(new BaseException(errorCode))
                    .given(referralService).deleteReferral(any(), any(), any());

            mockMvc.perform(delete(BASE + "/{referralId}", referralId)
                            .header("X-User-Id", userId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().is(errorCode.getStatus().value()))
                    .andExpect(jsonPath("$.code").value(errorCode.name()));
        }

        @Test
        @DisplayName("X-User-Id가 없으면 400 MISSING_HEADER이고 삭제하지 않는다")
        void missingUser() throws Exception {
            mockMvc.perform(delete(BASE + "/{referralId}", referralId)
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

            then(referralService).should(never()).deleteReferral(any(), any(), any());
        }
    }

    // ==============================
    // 도우미
    // ==============================

    private ReferralResponse referral(ReferralStatus status) {
        return ReferralResponse.builder()
                .id(referralId)
                .referringInstitutionId(institutionId)
                .referrerId(userId)
                .examTypeId(examTypeId)
                .currentReservationId(reservationId)
                .referralStatus(status)
                .patientName("홍길동")
                .build();
    }

    private ReservationResponse reservation(int attemptNo, ReservationStatus status) {
        LocalDateTime start = LocalDateTime.of(2026, 12, 1, 10, 0);
        return ReservationResponse.builder()
                .id(reservationId)
                .referralId(referralId)
                .attemptNo(attemptNo)
                .slotId(slotId)
                .examInstitutionId(examInstitutionId)
                .equipmentId(equipmentId)
                .examDate(start)
                .startTime(start)
                .endTime(start.plusHours(1))
                .reservationStatus(status)
                .build();
    }

    private ReferralHistoryResponse history(UUID eventId, ReferralStatus from, ReferralStatus to, Reason reason, UUID changedBy) {
        return ReferralHistoryResponse.builder()
                .id(UUID.randomUUID())
                .referralId(referralId)
                .reservationId(reservationId)
                .eventId(eventId)
                .fromStatus(from)
                .toStatus(to)
                .reason(reason)
                .occurredAt(LocalDateTime.of(2026, 11, 11, 9, 0))
                .changedBy(changedBy)
                .build();
    }

    private String reservationJson() {
        return reservationJsonWithout(null);
    }

    // 지정한 필드를 뺀 첫 예약 JSON
    private String reservationJsonWithout(String excluded) {
        StringBuilder json = new StringBuilder("{");
        appendField(json, "slotId", "\"" + slotId + "\"", excluded);
        appendField(json, "examInstitutionId", "\"" + examInstitutionId + "\"", excluded);
        appendField(json, "equipmentId", "\"" + equipmentId + "\"", excluded);
        appendField(json, "examDate", "\"2026-12-01T10:00:00\"", excluded);
        appendField(json, "startTime", "\"2026-12-01T10:00:00\"", excluded);
        appendField(json, "endTime", "\"2026-12-01T11:00:00\"", excluded);
        return json.append("}").toString();
    }

    private String referralBody(String reservationJson) {
        return referralBodyWithout(null, reservationJson);
    }

    private String referralBodyWithout(String excluded) {
        return referralBodyWithout(excluded, reservationJson());
    }

    private String referralBodyWithout(String excluded, String reservationJson) {
        StringBuilder json = new StringBuilder("{");
        appendField(json, "referringInstitutionId", "\"" + institutionId + "\"", excluded);
        appendField(json, "examTypeId", "\"" + examTypeId + "\"", excluded);
        appendField(json, "patientName", "\"홍길동\"", excluded);
        appendField(json, "idempotencyKey", "\"" + idempotencyKey + "\"", excluded);
        appendField(json, "reservation", reservationJson, excluded);
        return json.append("}").toString();
    }

    private static void appendField(StringBuilder json, String name, String value, String excluded) {
        if (name.equals(excluded)) {
            return;
        }
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(name).append("\":").append(value);
    }
}
