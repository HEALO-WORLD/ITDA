package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.EquipmentIncidentQueryService;
import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.application.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @ModelAttribute 값을 타입으로 변환하지 못했을 때 응답을 검증한다 (GlobalExceptionHandler)
// 오류 코드와 상태 코드는 그대로 유지하고, 내부 패키지명·클래스명·예외 원문은 응답에 나가지 않아야 한다
@WebMvcTest({EquipmentIncidentController.class, ReferralController.class, ReservationController.class,
        ExaminationController.class})
@ActiveProfiles("test")
class BindingErrorResponseTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EquipmentIncidentQueryService equipmentIncidentQueryService;

    @MockitoBean
    private ReferralService referralService;

    @MockitoBean
    private ReferralHistoryService referralHistoryService;

    @MockitoBean
    private ReservationService reservationService;

    @MockitoBean
    private ExaminationResultService examinationResultService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @ParameterizedTest(name = "{0}?{1}={2}")
    @CsvSource({
            "/api/v1/equipment-incidents, status, RESOLVED",
            "/api/v1/equipment-incidents, equipmentId, not-a-uuid",
            "/api/v1/equipment-incidents, institutionId, not-a-uuid",
            "/api/v1/referrals, status, UNKNOWN",
            "/api/v1/referrals, referrerId, not-a-uuid",
            "/api/v1/referrals/reservations, status, UNKNOWN",
            "/api/v1/referrals/reservations, equipmentId, not-a-uuid"
    })
    @DisplayName("검색 조건 변환 실패는 400 VALIDATION_FAILED를 유지하고 내부 정보·입력값을 응답에 담지 않는다")
    void bindingFailureHidesInternalDetails(String path, String param, String value) throws Exception {
        mockMvc.perform(get(path)
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-Institution-Id", UUID.randomUUID().toString())
                        .header("X-Role", "ADMIN")
                        .param(param, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("유효성 검사에 실패했습니다."))
                .andExpect(content().string(not(containsString("healoworld"))))
                .andExpect(content().string(not(containsString("java."))))
                .andExpect(content().string(not(containsString("Failed to convert"))))
                .andExpect(content().string(not(containsString(value))));
    }

    @Test
    @DisplayName("@Valid 검증 실패는 기존처럼 DTO에 작성한 안내 메시지를 그대로 응답한다")
    void validationMessageIsKept() throws Exception {
        mockMvc.perform(post("/api/v1/examinations/{id}/results", UUID.randomUUID())
                        .header("X-Institution-Id", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("검사 결과 요약(summary)은 필수입니다."));
    }

    @Test
    @DisplayName("@RequestParam·경로 변수 형식 오류는 기존 400 INVALID_INPUT을 유지한다")
    void typeMismatchIsStillInvalidInput() throws Exception {
        mockMvc.perform(get("/api/v1/equipment-incidents/{id}", "not-a-uuid")
                        .header("X-Institution-Id", UUID.randomUUID().toString())
                        .header("X-Role", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(content().string(not(containsString("healoworld"))));
    }
}
