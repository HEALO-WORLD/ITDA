package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
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
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ReservationController 웹 계층 테스트 (헤더 위조 방지·DELETE 405는 AuthHeaderContractTest).
 * <p>
 * 예약 조회·목록·취소 API의 요청 바인딩, 헤더 누락, 업무 예외의 상태 코드 변환, 응답 형식을 확인한다.
 * ReferralController도 함께 올려 /api/v1/referrals/reservations 경로가 의뢰 단건 조회(/{referralId})로 가지 않는지 확인한다.
 */
@WebMvcTest({ReservationController.class, ReferralController.class})
@ActiveProfiles("test")
class ReservationControllerTest {

    private static final String BASE = "/api/v1/referrals/reservations";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReservationService reservationService;

    // 함께 올린 ReferralController의 의존성 (라우팅 확인용)
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
    private final UUID reservationId = UUID.randomUUID();
    private final UUID referralId = UUID.randomUUID();
    private final UUID examInstitutionId = UUID.randomUUID();

    // ==============================
    // GET /{reservationId}
    // ==============================

    @Nested
    @DisplayName("예약 단건 조회 GET /api/v1/referrals/reservations/{reservationId}")
    class GetReservation {

        @Test
        @DisplayName("200과 예약 필드를 돌려주고, 기관·예약 ID·역할을 Service에 넘긴다")
        void ok() throws Exception {
            given(reservationService.getReservation(institutionId, reservationId, RoleType.EXAMINER))
                    .willReturn(reservation(ReservationStatus.FAILED, FailureReason.SLOT_CONFLICT));

            mockMvc.perform(get(BASE + "/{reservationId}", reservationId)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "EXAMINER"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("예약 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.id").value(reservationId.toString()))
                    .andExpect(jsonPath("$.data.referralId").value(referralId.toString()))
                    .andExpect(jsonPath("$.data.attemptNo").value(1))
                    .andExpect(jsonPath("$.data.examInstitutionId").value(examInstitutionId.toString()))
                    .andExpect(jsonPath("$.data.reservationStatus").value("FAILED"))
                    .andExpect(jsonPath("$.data.failureReason").value("SLOT_CONFLICT"))
                    .andExpect(jsonPath("$.data.startTime").exists());

            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("없거나 관계없는 기관의 예약이면 404 RESERVATION_NOT_FOUND")
        void notFound() throws Exception {
            given(reservationService.getReservation(any(), any(), any()))
                    .willThrow(new BaseException(ErrorCode.RESERVATION_NOT_FOUND));

            mockMvc.perform(get(BASE + "/{reservationId}", reservationId)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        }
    }

    // ==============================
    // GET (목록)
    // ==============================

    @Nested
    @DisplayName("예약 목록 조회 GET /api/v1/referrals/reservations")
    class GetReservations {

        @Test
        @DisplayName("의뢰 단건 조회(/{referralId})가 아니라 예약 목록으로 연결되고, 쿼리 파라미터가 검색 조건으로 바인딩된다")
        void routesToReservationListAndBindsCondition() throws Exception {
            given(reservationService.getReservations(eq(institutionId), any(ReservationSearchCondition.class), eq(RoleType.EXAMINER)))
                    .willReturn(new PagedResponse<>(List.of(reservation(ReservationStatus.CONFIRMED, null)), 1, 1, 10, 0));

            mockMvc.perform(get(BASE)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "EXAMINER")
                            .param("referralId", referralId.toString())
                            .param("status", "CONFIRMED")
                            .param("failureReason", "HOLD_EXPIRED")
                            .param("examDateFrom", "2026-12-01T00:00:00")
                            .param("examDateTo", "2026-12-31T23:59:59")
                            .param("sort", "examDate,asc"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("예약 목록 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.contents[0].id").value(reservationId.toString()))
                    .andExpect(jsonPath("$.data.totalElements").value(1));

            verifyNoInteractions(referralService);
            ArgumentCaptor<ReservationSearchCondition> captor = ArgumentCaptor.forClass(ReservationSearchCondition.class);
            then(reservationService).should().getReservations(eq(institutionId), captor.capture(), eq(RoleType.EXAMINER));
            ReservationSearchCondition cond = captor.getValue();
            assertThat(cond.referralId()).isEqualTo(referralId);
            assertThat(cond.status()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(cond.failureReason()).isEqualTo(FailureReason.HOLD_EXPIRED);
            assertThat(cond.examDateFrom()).isEqualTo(LocalDateTime.of(2026, 12, 1, 0, 0));
            assertThat(cond.examDateTo()).isEqualTo(LocalDateTime.of(2026, 12, 31, 23, 59, 59));
            assertThat(cond.toPageable().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "examDate"));
        }

        @Test
        @DisplayName("정의되지 않은 예약 상태로 필터하면 400 VALIDATION_FAILED이고 Service를 호출하지 않는다")
        void invalidStatus() throws Exception {
            mockMvc.perform(get(BASE)
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER")
                            .param("status", "UNKNOWN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(reservationService);
        }
    }

    // ==============================
    // PATCH /{reservationId}/cancel
    // ==============================

    @Nested
    @DisplayName("예약 취소 PATCH /api/v1/referrals/reservations/{reservationId}/cancel")
    class Cancel {

        @Test
        @DisplayName("200과 data null을 돌려주고, 사용자·기관·역할·예약 ID를 Service에 넘긴다")
        void ok() throws Exception {
            mockMvc.perform(cancelRequest())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("취소가 완료되었습니다."))
                    .andExpect(jsonPath("$.data").doesNotExist());

            then(reservationService).should().cancelReservation(userId, institutionId, RoleType.REQUESTER, reservationId);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = ErrorCode.class, names = {
                "RESERVATION_NOT_FOUND", "REFERRAL_NOT_FOUND", "RESERVATION_CANCEL_DEADLINE_PASSED",
                "INVALID_RESERVATION_STATUS", "INVALID_REFERRAL_STATUS", "EXAMINATION_CANCEL_NOT_ALLOWED"})
        @DisplayName("Service의 업무 예외는 ErrorCode의 상태 코드·코드·메시지로 응답한다 (404·409)")
        void businessErrors(ErrorCode errorCode) throws Exception {
            willThrow(new BaseException(errorCode))
                    .given(reservationService).cancelReservation(any(), any(), any(), any());

            mockMvc.perform(cancelRequest())
                    .andExpect(status().is(errorCode.getStatus().value()))
                    .andExpect(jsonPath("$.code").value(errorCode.name()))
                    .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
        }

        @ParameterizedTest(name = "{0} 누락")
        @ValueSource(strings = {"X-User-Id", "X-Institution-Id", "X-Role"})
        @DisplayName("요청자 헤더가 하나라도 없으면 400 MISSING_HEADER이고 취소하지 않는다")
        void missingHeader(String missing) throws Exception {
            MockHttpServletRequestBuilder request = patch(BASE + "/{reservationId}/cancel", reservationId);
            if (!missing.equals("X-User-Id")) {
                request.header("X-User-Id", userId.toString());
            }
            if (!missing.equals("X-Institution-Id")) {
                request.header("X-Institution-Id", institutionId.toString());
            }
            if (!missing.equals("X-Role")) {
                request.header("X-Role", "REQUESTER");
            }

            mockMvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

            verifyNoInteractions(reservationService);
        }

        @Test
        @DisplayName("예약 ID가 UUID 형식이 아니면 400 INVALID_INPUT")
        void invalidId() throws Exception {
            mockMvc.perform(patch(BASE + "/{reservationId}/cancel", "not-a-uuid")
                            .header("X-User-Id", userId.toString())
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(reservationService);
        }

        @Test
        @DisplayName("역할 값이 Auth 명세에 없으면 400 INVALID_INPUT")
        void invalidRole() throws Exception {
            mockMvc.perform(patch(BASE + "/{reservationId}/cancel", reservationId)
                            .header("X-User-Id", userId.toString())
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "USER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(reservationService);
        }

        @Test
        @DisplayName("PATCH가 아닌 메서드(POST)로 취소하면 405 UNSUPPORTED_REQUEST")
        void wrongMethod() throws Exception {
            mockMvc.perform(post(BASE + "/{reservationId}/cancel", reservationId)
                            .header("X-User-Id", userId.toString())
                            .header("X-Institution-Id", institutionId.toString())
                            .header("X-Role", "REQUESTER"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"));

            verifyNoInteractions(reservationService);
        }

        private MockHttpServletRequestBuilder cancelRequest() {
            return patch(BASE + "/{reservationId}/cancel", reservationId)
                    .header("X-User-Id", userId.toString())
                    .header("X-Institution-Id", institutionId.toString())
                    .header("X-Role", "REQUESTER");
        }
    }

    private ReservationResponse reservation(ReservationStatus status, FailureReason failureReason) {
        LocalDateTime start = LocalDateTime.of(2026, 12, 1, 10, 0);
        return ReservationResponse.builder()
                .id(reservationId)
                .referralId(referralId)
                .attemptNo(1)
                .slotId(UUID.randomUUID())
                .examInstitutionId(examInstitutionId)
                .equipmentId(UUID.randomUUID())
                .examDate(start)
                .startTime(start)
                .endTime(start.plusHours(1))
                .reservationStatus(status)
                .failureReason(failureReason)
                .build();
    }
}
