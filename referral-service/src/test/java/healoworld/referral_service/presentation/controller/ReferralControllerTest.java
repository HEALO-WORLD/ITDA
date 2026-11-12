package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.ExaminationResultResponse;

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
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReferralController.class)
@ActiveProfiles("test")
class ReferralControllerTest {

    private static final String URL = "/api/v1/referrals/{referralId}/results";

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

    private final UUID referralId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();


    // ==============================
    // 공통 테스트 메서드
    // ==============================

    // 정상 요청에 필요한 헤더를 설정한다.
    private MockHttpServletRequestBuilder resultRequest(String role) {
        return get(URL, referralId)
                .header("X-Institution-Id", institutionId.toString())
                .header("X-Role", role);
    }

    // Service에서 발생한 예외가 HTTP 응답으로 변환되는지 검증하기 위한 설정
    private void mockResultError(Role role, ErrorCode errorCode) {
        given(examinationResultService.getResultByReferralId(
                referralId,
                institutionId,
                role
        )).willThrow(new BaseException(errorCode));
    }


    // ==============================
    // 정상 조회
    // ==============================

    @Test
    @DisplayName("의뢰기관 담당자는 검사 결과를 정상 조회할 수 있다")
    void getResultByReferralIdSuccess() throws Exception {
        // given
        UUID examinationId = UUID.randomUUID();
        UUID resultId = UUID.randomUUID();

        ExaminationResultResponse response = new ExaminationResultResponse(
                resultId,
                examinationId,
                referralId,
                "CT 검사 결과 정상",
                "특이 소견 없음",
                LocalDateTime.of(2026, 11, 2, 14, 0)
        );

        given(examinationResultService.getResultByReferralId(
                referralId,
                institutionId,
                Role.REQUESTER
        )).willReturn(response);

        // when & then
        mockMvc.perform(resultRequest("REQUESTER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id")
                        .value(resultId.toString()))
                .andExpect(jsonPath("$.data.examinationId")
                        .value(examinationId.toString()))
                .andExpect(jsonPath("$.data.referralId")
                        .value(referralId.toString()))
                .andExpect(jsonPath("$.data.summary")
                        .value("CT 검사 결과 정상"))
                .andExpect(jsonPath("$.data.notes")
                        .value("특이 소견 없음"))
                .andExpect(jsonPath("$.data.registeredAt").exists());
    }


    // ==============================
    // 접근 권한 및 예외 응답
    // ==============================

    @Test
    @DisplayName("EXAMINER 조회 요청은 Service의 권한 거부에 따라 403을 반환한다")
    void examinerAccessDenied() throws Exception {
        // given
        mockResultError(
                Role.EXAMINER,
                ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
        );

        // when & then
        mockMvc.perform(resultRequest("EXAMINER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("EXAMINATION_RESULT_READ_ACCESS_DENIED"));

        // Controller가 역할 정보를 Service에 전달했는지 확인
        then(examinationResultService)
                .should()
                .getResultByReferralId(
                        referralId,
                        institutionId,
                        Role.EXAMINER
                );
    }

    @Test
    @DisplayName("기관 접근 권한이 없으면 Service의 예외에 따라 403을 반환한다")
    void otherInstitutionAccessDenied() throws Exception {
        // given
        mockResultError(
                Role.REQUESTER,
                ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
        );

        // when & then
        mockMvc.perform(resultRequest("REQUESTER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("EXAMINATION_RESULT_READ_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("검사 결과가 등록되지 않았다면 404를 반환한다")
    void resultNotFound() throws Exception {
        // given
        mockResultError(
                Role.REQUESTER,
                ErrorCode.EXAMINATION_RESULT_NOT_FOUND
        );

        // when & then
        mockMvc.perform(resultRequest("REQUESTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("EXAMINATION_RESULT_NOT_FOUND"));
    }


    // ==============================
    // 요청 헤더 및 경로 변수 검증
    // ==============================

    @Test
    @DisplayName("기관 ID 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void institutionHeaderRequired() throws Exception {
        // when & then
        mockMvc.perform(get(URL, referralId)
                        .header("X-Role", "REQUESTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("MISSING_HEADER"));

        // 필수 헤더 누락으로 Service가 호출되지 않아야 한다.
        then(examinationResultService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("의뢰 ID가 UUID 형식이 아니면 400 INVALID_INPUT을 반환한다")
    void invalidReferralId() throws Exception {
        // when & then
        mockMvc.perform(get(URL, "not-a-uuid")
                        .header("X-Institution-Id", institutionId.toString())
                        .header("X-Role", "REQUESTER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));

        // 경로 변수 변환에 실패하면 Service를 호출하지 않는다.
        then(examinationResultService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("유효하지 않은 역할값이면 400 INVALID_INPUT을 반환한다")
    void invalidRole() throws Exception {
        // when & then
        mockMvc.perform(resultRequest("UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_INPUT"));

        // 역할값 변환에 실패하면 Service를 호출하지 않는다.
        then(examinationResultService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("역할 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
    void roleHeaderRequired() throws Exception {
        // when & then
        mockMvc.perform(get(URL, referralId)
                        .header("X-Institution-Id", institutionId.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("MISSING_HEADER"));

        // 필수 헤더 누락으로 Service가 호출되지 않아야 한다.
        then(examinationResultService).shouldHaveNoInteractions();
    }
}