package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExaminationInternalController.class)
class ExaminationInternalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExaminationService examinationService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    @DisplayName("의뢰별 검사 조회 - 명세 필드로 200 응답")
    void getByReferralId() throws Exception {
        UUID referralId = UUID.randomUUID();
        UUID examinationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        given(examinationService.getByReferralId(referralId)).willReturn(new ExaminationResponse(examinationId,
                referralId, institutionId, ExaminationStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 28, 9, 0), null));

        mockMvc.perform(get("/api/v1/internal/examinations/by-referral/{referralId}", referralId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.examinationId").value(examinationId.toString()))
                .andExpect(jsonPath("$.data.referralId").value(referralId.toString()))
                .andExpect(jsonPath("$.data.examInstitutionId").value(institutionId.toString()))
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.startedAt").exists())
                .andExpect(jsonPath("$.data.completedAt").doesNotExist());
    }

    @Test
    @DisplayName("검사가 없으면 404 EXAMINATION_NOT_FOUND")
    void notFound() throws Exception {
        UUID referralId = UUID.randomUUID();
        given(examinationService.getByReferralId(referralId))
                .willThrow(new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/internal/examinations/by-referral/{referralId}", referralId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAMINATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("referralId가 UUID 형식이 아니면 400 INVALID_INPUT")
    void invalidReferralId() throws Exception {
        mockMvc.perform(get("/api/v1/internal/examinations/by-referral/{referralId}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
