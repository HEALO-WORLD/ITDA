package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.EquipmentIncidentQueryService;
import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 실제 Controller + 실제 EquipmentIncidentQueryService로 JSON 응답에 lastError가 역할별로 어떻게 담기는지 검증한다
// (Repository만 Mock이며 DB는 사용하지 않는다)
@WebMvcTest(EquipmentIncidentController.class)
@Import(EquipmentIncidentQueryService.class)
@ActiveProfiles("test")
class EquipmentIncidentLastErrorResponseTest {

    private static final String INTERNAL_ERROR = "[RETRYABLE] PSQLException: ERROR: 가상 SQL 오류 detail";
    private static final LocalDateTime OCCURRED_AT = LocalDateTime.of(2026, 11, 2, 14, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @MockitoBean
    private IncidentReferralRepository incidentReferralRepository;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID institutionId = UUID.randomUUID();
    private UUID incidentId;

    @BeforeEach
    void setUp() {
        EquipmentIncident incident = EquipmentIncident.occur(UUID.randomUUID(), UUID.randomUUID(), institutionId,
                "가상 장애 사유", OCCURRED_AT, OCCURRED_AT, OCCURRED_AT.plusHours(3));
        incidentId = UUID.randomUUID();
        ReflectionTestUtils.setField(incident, "id", incidentId);
        IncidentReferral failed = IncidentReferral.pending(incident, UUID.randomUUID(), UUID.randomUUID());
        ReflectionTestUtils.setField(failed, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(failed, "processingStatus", IncidentProcessingStatus.FAILED);
        ReflectionTestUtils.setField(failed, "retryCount", 1);
        ReflectionTestUtils.setField(failed, "lastError", INTERNAL_ERROR);
        given(equipmentIncidentRepository.findById(incidentId)).willReturn(Optional.of(incident));
        given(incidentReferralRepository.findByIncident_Id(eq(incidentId), any()))
                .willReturn(new PageImpl<>(List.of(failed)));
    }

    @Test
    @DisplayName("EXAMINER 응답 JSON에는 lastError가 null이고 내부 예외 메시지·클래스명이 들어 있지 않다")
    void examinerResponseHasNoInternalError() throws Exception {
        mockMvc.perform(get("/api/v1/equipment-incidents/{incidentId}/referrals", incidentId)
                        .header("X-Institution-Id", institutionId.toString())
                        .header("X-Role", "EXAMINER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contents[0].processingStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.contents[0].retryCount").value(1))
                .andExpect(jsonPath("$.data.contents[0].lastError").doesNotExist())
                .andExpect(content().string(not(containsString("PSQLException"))))
                .andExpect(content().string(not(containsString("가상 SQL 오류"))));
    }

    @Test
    @DisplayName("ADMIN 응답 JSON에는 기존처럼 lastError 원문이 담긴다")
    void adminResponseHasLastError() throws Exception {
        mockMvc.perform(get("/api/v1/equipment-incidents/{incidentId}/referrals", incidentId)
                        .header("X-Institution-Id", UUID.randomUUID().toString())
                        .header("X-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contents[0].lastError").value(INTERNAL_ERROR));
    }

    @Test
    @DisplayName("다른 기관의 EXAMINER는 기존대로 404이고 작업 정보가 응답되지 않는다")
    void otherInstitutionExaminerStillGets404() throws Exception {
        mockMvc.perform(get("/api/v1/equipment-incidents/{incidentId}/referrals", incidentId)
                        .header("X-Institution-Id", UUID.randomUUID().toString())
                        .header("X-Role", "EXAMINER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EQUIPMENT_INCIDENT_NOT_FOUND"))
                .andExpect(content().string(not(containsString("PSQLException"))));
    }
}
