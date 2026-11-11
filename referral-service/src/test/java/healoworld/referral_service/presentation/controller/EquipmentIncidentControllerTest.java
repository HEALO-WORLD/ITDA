package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.EquipmentIncidentQueryService;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentSummaryResponse;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HTTP 요청 → 헤더·쿼리 파라미터 바인딩 → Service 호출 → 응답·오류 코드 변환을 검증한다 (Service는 Mock, DB 미사용)
@WebMvcTest(EquipmentIncidentController.class)
@ActiveProfiles("test")
class EquipmentIncidentControllerTest {

    private static final String BASE = "/api/v1/equipment-incidents";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 2, 14, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EquipmentIncidentQueryService equipmentIncidentQueryService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID institutionId = UUID.randomUUID();
    private final UUID incidentId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();

    private MockHttpServletRequestBuilder withAuth(MockHttpServletRequestBuilder request, String role) {
        return request.header("X-Institution-Id", institutionId.toString()).header("X-Role", role);
    }

    @Test
    @DisplayName("목록: 헤더와 쿼리 파라미터가 Service로 전달되고 페이지 응답을 200으로 돌려준다")
    void list() throws Exception {
        UUID otherInstitutionId = UUID.randomUUID();
        EquipmentIncidentSearchCondition expected = new EquipmentIncidentSearchCondition(otherInstitutionId,
                equipmentId, EquipmentIncidentStatus.OCCURRED, 1, 30);
        given(equipmentIncidentQueryService.getIncidents(Role.ADMIN, institutionId, expected)).willReturn(
                new PagedResponse<>(List.of(new EquipmentIncidentResponse(incidentId, equipmentId, otherInstitutionId,
                        "가상 장애 사유", EquipmentIncidentStatus.OCCURRED, OCCURRED_AT, OCCURRED_AT,
                        OCCURRED_AT.plusHours(3), OCCURRED_AT)), 31, 2, 30, 1));

        mockMvc.perform(withAuth(get(BASE), "ADMIN")
                        .param("institutionId", otherInstitutionId.toString())
                        .param("equipmentId", equipmentId.toString())
                        .param("status", "OCCURRED")
                        .param("page", "1")
                        .param("size", "30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contents[0].incidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$.data.contents[0].status").value("OCCURRED"))
                .andExpect(jsonPath("$.data.contents[0].startAt").exists())
                .andExpect(jsonPath("$.data.totalElements").value(31))
                .andExpect(jsonPath("$.data.number").value(1));
    }

    @Test
    @DisplayName("상세: 명세 필드와 영향 구간·처리 건수를 200으로 돌려준다")
    void detail() throws Exception {
        given(equipmentIncidentQueryService.getIncident(Role.EXAMINER, institutionId, incidentId)).willReturn(
                new EquipmentIncidentDetailResponse(incidentId, equipmentId, institutionId, "가상 장애 사유",
                        EquipmentIncidentStatus.OCCURRED, OCCURRED_AT, OCCURRED_AT, OCCURRED_AT.plusHours(3),
                        10, 3, 4, 1, 2, OCCURRED_AT, OCCURRED_AT));

        mockMvc.perform(withAuth(get(BASE + "/{incidentId}", incidentId), "EXAMINER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.incidentId").value(incidentId.toString()))
                .andExpect(jsonPath("$.data.institutionId").value(institutionId.toString()))
                .andExpect(jsonPath("$.data.reason").value("가상 장애 사유"))
                .andExpect(jsonPath("$.data.endAt").exists())
                .andExpect(jsonPath("$.data.totalReferrals").value(10))
                .andExpect(jsonPath("$.data.completedCount").value(3))
                .andExpect(jsonPath("$.data.failedCount").value(4))
                .andExpect(jsonPath("$.data.pendingCount").value(1))
                .andExpect(jsonPath("$.data.processingCount").value(2))
                .andExpect(jsonPath("$.data.updatedAt").exists());
    }

    @Test
    @DisplayName("영향 의뢰: status·page·size가 전달되고 작업 처리 현황을 200으로 돌려준다")
    void referrals() throws Exception {
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        given(equipmentIncidentQueryService.getIncidentReferrals(Role.EXAMINER, institutionId, incidentId,
                new IncidentReferralSearchCondition(IncidentProcessingStatus.FAILED, 0, 10))).willReturn(
                new PagedResponse<>(List.of(new IncidentReferralResponse(UUID.randomUUID(), incidentId, referralId,
                        reservationId, IncidentProcessingStatus.FAILED, 1, OCCURRED_AT, null, "[RETRYABLE] 가상 오류",
                        null, null, false)), 1, 1, 10, 0));

        mockMvc.perform(withAuth(get(BASE + "/{incidentId}/referrals", incidentId), "EXAMINER")
                        .param("status", "FAILED")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contents[0].referralId").value(referralId.toString()))
                .andExpect(jsonPath("$.data.contents[0].reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.data.contents[0].processingStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.contents[0].retryCount").value(1))
                .andExpect(jsonPath("$.data.contents[0].lastError").value("[RETRYABLE] 가상 오류"))
                .andExpect(jsonPath("$.data.contents[0].manualReviewRequired").value(false));
    }

    @Test
    @DisplayName("영향 의뢰: 수동 확인 대상은 processingStatus SUCCEEDED와 manualReviewRequired true가 함께 내려간다")
    void referralManualReviewRequired() throws Exception {
        given(equipmentIncidentQueryService.getIncidentReferrals(Role.EXAMINER, institutionId, incidentId,
                new IncidentReferralSearchCondition(null, 0, 10))).willReturn(
                new PagedResponse<>(List.of(new IncidentReferralResponse(UUID.randomUUID(), incidentId,
                        UUID.randomUUID(), UUID.randomUUID(), IncidentProcessingStatus.SUCCEEDED, 0, null, null,
                        null, "SKIPPED_IN_PROGRESS", OCCURRED_AT, true)), 1, 1, 10, 0));

        mockMvc.perform(withAuth(get(BASE + "/{incidentId}/referrals", incidentId), "EXAMINER")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contents[0].processingStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.contents[0].resultCode").value("SKIPPED_IN_PROGRESS"))
                .andExpect(jsonPath("$.data.contents[0].manualReviewRequired").value(true));
    }

    @Test
    @DisplayName("X-Role이 없으면 400 MISSING_HEADER이고 Service를 호출하지 않는다")
    void roleHeaderRequired() throws Exception {
        mockMvc.perform(get(BASE).header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        then(equipmentIncidentQueryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("X-Institution-Id가 없으면 400 MISSING_HEADER")
    void institutionHeaderRequired() throws Exception {
        mockMvc.perform(get(BASE + "/{incidentId}", incidentId).header("X-Role", "EXAMINER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        then(equipmentIncidentQueryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Auth 명세에 없는 역할 값(USER)이면 400 INVALID_INPUT")
    void unknownRole() throws Exception {
        mockMvc.perform(withAuth(get(BASE), "USER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        then(equipmentIncidentQueryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정의되지 않은 장애 상태 값으로 필터하면 400이고 Service를 호출하지 않는다")
    void unknownStatusFilter() throws Exception {
        mockMvc.perform(withAuth(get(BASE), "ADMIN").param("status", "RESOLVED"))
                .andExpect(status().isBadRequest());
        then(equipmentIncidentQueryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("장애 ID가 UUID 형식이 아니면 400 INVALID_INPUT")
    void invalidIncidentId() throws Exception {
        mockMvc.perform(withAuth(get(BASE + "/{incidentId}", "not-a-uuid"), "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("REQUESTER 조회는 403 EQUIPMENT_INCIDENT_ACCESS_DENIED")
    void requesterIsForbidden() throws Exception {
        given(equipmentIncidentQueryService.getIncidents(eq(Role.REQUESTER), eq(institutionId), any()))
                .willThrow(new BaseException(ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED));

        mockMvc.perform(withAuth(get(BASE), "REQUESTER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_INCIDENT_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("없는 장애이거나 다른 기관의 장애면 404 EQUIPMENT_INCIDENT_NOT_FOUND")
    void notFound() throws Exception {
        given(equipmentIncidentQueryService.getIncident(Role.EXAMINER, institutionId, incidentId))
                .willThrow(new BaseException(ErrorCode.EQUIPMENT_INCIDENT_NOT_FOUND));

        mockMvc.perform(withAuth(get(BASE + "/{incidentId}", incidentId), "EXAMINER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_INCIDENT_NOT_FOUND"));
    }

    // ===== 현황 집계 =====

    @Test
    @DisplayName("현황: /summary는 장애 상세(/{incidentId})로 가지 않고, 헤더를 Service로 넘겨 세 값을 200으로 돌려준다")
    void summary() throws Exception {
        given(equipmentIncidentQueryService.getSummary(Role.EXAMINER, institutionId, null))
                .willReturn(new EquipmentIncidentSummaryResponse(institutionId, OCCURRED_AT, 1, 7, 2));

        mockMvc.perform(withAuth(get(BASE + "/summary"), "EXAMINER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.institutionId").value(institutionId.toString()))
                .andExpect(jsonPath("$.data.asOf").value("2026-11-02T14:00:00"))
                .andExpect(jsonPath("$.data.activeDowntimeEquipmentCount").value(1))
                .andExpect(jsonPath("$.data.affectedReferralCount").value(7))
                .andExpect(jsonPath("$.data.needsRescheduleReferralCount").value(2));
        then(equipmentIncidentQueryService).should(never()).getIncident(any(), any(), any());
    }

    @Test
    @DisplayName("현황: ADMIN의 institutionId 쿼리 파라미터가 Service로 전달된다")
    void summaryAdminInstitutionParam() throws Exception {
        UUID targetInstitutionId = UUID.randomUUID();
        given(equipmentIncidentQueryService.getSummary(Role.ADMIN, institutionId, targetInstitutionId))
                .willReturn(new EquipmentIncidentSummaryResponse(targetInstitutionId, OCCURRED_AT, 0, 0, 0));

        mockMvc.perform(withAuth(get(BASE + "/summary"), "ADMIN")
                        .param("institutionId", targetInstitutionId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.institutionId").value(targetInstitutionId.toString()))
                .andExpect(jsonPath("$.data.needsRescheduleReferralCount").value(0));
    }

    @Test
    @DisplayName("현황: ADMIN이 institutionId 없이 요청하면 400 VALIDATION_FAILED")
    void summaryAdminWithoutInstitution() throws Exception {
        given(equipmentIncidentQueryService.getSummary(Role.ADMIN, institutionId, null))
                .willThrow(new BaseException(ErrorCode.VALIDATION_FAILED));

        mockMvc.perform(withAuth(get(BASE + "/summary"), "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("현황: REQUESTER는 403, 헤더 누락은 400 MISSING_HEADER, institutionId 형식 오류는 400 INVALID_INPUT")
    void summaryErrors() throws Exception {
        given(equipmentIncidentQueryService.getSummary(eq(Role.REQUESTER), eq(institutionId), any()))
                .willThrow(new BaseException(ErrorCode.EQUIPMENT_INCIDENT_ACCESS_DENIED));

        mockMvc.perform(withAuth(get(BASE + "/summary"), "REQUESTER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_INCIDENT_ACCESS_DENIED"));
        mockMvc.perform(get(BASE + "/summary").header("X-Role", "EXAMINER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        mockMvc.perform(withAuth(get(BASE + "/summary"), "ADMIN").param("institutionId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
