package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// HTTP 요청 → 헤더·본문 검증 → Service 호출 → 응답 코드 변환까지만 검증한다 (Service는 Mock, DB 미사용)
@WebMvcTest(ExaminationController.class)
@ActiveProfiles("test")
class ExaminationControllerTest {

    private static final String URL = "/api/v1/examinations/{examinationId}/results";
    private static final String INSTITUTION_HEADER = "X-Institution-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExaminationService examinationService;

    @MockitoBean
    private ExaminationResultService examinationResultService;

    // 메인 클래스의 @EnableJpaAuditing이 웹 슬라이스 테스트에서도 JPA 메타모델을 찾기 때문에 대체한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID examinationId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();

    private ResultActions postResult(String body) throws Exception {
        return mockMvc.perform(post(URL, examinationId)
                .header(INSTITUTION_HEADER, institutionId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void givenServiceThrows(ErrorCode errorCode) {
        given(examinationResultService.register(any(), any(), any(), any()))
                .willThrow(new BaseException(errorCode));
    }

    @Test
    @DisplayName("정상 등록 - 201 Created와 결과 ID·검사 ID·등록 시각을 응답하고 헤더의 기관 ID를 Service에 넘긴다")
    void register() throws Exception {
        UUID resultId = UUID.randomUUID();
        given(examinationResultService.register(examinationId, institutionId, "가상 결과 요약", "가상 추가 소견"))
                .willReturn(new ExaminationResultCreateResponse(resultId, examinationId,
                        LocalDateTime.of(2026, 10, 30, 14, 30)));

        postResult("""
                {"summary": "가상 결과 요약", "notes": "가상 추가 소견"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resultId").value(resultId.toString()))
                .andExpect(jsonPath("$.data.examinationId").value(examinationId.toString()))
                .andExpect(jsonPath("$.data.registeredAt").exists());
    }

    @Test
    @DisplayName("검사 시작 요청 시 200 OK와 변경된 상태를 반환한다")
    void changeStatusToInProgress() throws Exception {

        // given
        UUID referralId = UUID.randomUUID();

        ExaminationStatusChangeResponse response =
                new ExaminationStatusChangeResponse(
                        examinationId,
                        referralId,
                        ExaminationStatus.SCHEDULED,
                        ExaminationStatus.IN_PROGRESS,
                        LocalDateTime.of(2026, 11, 2, 10, 0)
                );

        given(examinationService.changeStatus(
                examinationId,
                institutionId,
                RoleType.EXAMINER,
                ExaminationStatus.IN_PROGRESS
        )).willReturn(response);

        // when & then
        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.examinationId")
                        .value(examinationId.toString()))
                .andExpect(jsonPath("$.data.previousStatus")
                        .value("SCHEDULED"))
                .andExpect(jsonPath("$.data.currentStatus")
                        .value("IN_PROGRESS"));
    }

    @Test
    @DisplayName("검사 완료 요청 시 200 OK와 COMPLETED 상태를 반환한다")
    void changeStatusToCompleted() throws Exception {

        // given
        UUID referralId = UUID.randomUUID();

        ExaminationStatusChangeResponse response =
                new ExaminationStatusChangeResponse(
                        examinationId,
                        referralId,
                        ExaminationStatus.IN_PROGRESS,
                        ExaminationStatus.COMPLETED,
                        LocalDateTime.of(2026, 11, 2, 11, 0)
                );

        given(examinationService.changeStatus(
                examinationId,
                institutionId,
                RoleType.EXAMINER,
                ExaminationStatus.COMPLETED
        )).willReturn(response);

        // when & then
        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "COMPLETED"}
                                    """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.previousStatus")
                        .value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.currentStatus")
                        .value("COMPLETED"));
    }

    @Test
    @DisplayName("notes 없이도 201로 등록된다")
    void registerWithoutNotes() throws Exception {
        given(examinationResultService.register(examinationId, institutionId, "가상 결과 요약", null))
                .willReturn(new ExaminationResultCreateResponse(UUID.randomUUID(), examinationId,
                        LocalDateTime.of(2026, 10, 30, 14, 30)));

        postResult("""
                {"summary": "가상 결과 요약"}
                """)
                .andExpect(status().isCreated());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{}", "{\"summary\": \"\"}", "{\"summary\": \"   \"}", "{\"summary\": null}"})
    @DisplayName("summary가 없거나 공백이면 400 VALIDATION_FAILED이고 Service를 호출하지 않는다")
    void summaryRequired(String body) throws Exception {
        postResult(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        then(examinationResultService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("X-Institution-Id 헤더가 없으면 400 MISSING_HEADER이고 Service를 호출하지 않는다")
    void institutionHeaderRequired() throws Exception {
        mockMvc.perform(post(URL, examinationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\": \"가상 결과 요약\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
        then(examinationResultService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("검사기관이 다르면 403 EXAMINATION_RESULT_ACCESS_DENIED")
    void otherInstitution() throws Exception {
        givenServiceThrows(ErrorCode.EXAMINATION_RESULT_ACCESS_DENIED);

        postResult("{\"summary\": \"가상 결과 요약\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EXAMINATION_RESULT_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("검사가 없으면 404 EXAMINATION_NOT_FOUND")
    void notFound() throws Exception {
        givenServiceThrows(ErrorCode.EXAMINATION_NOT_FOUND);

        postResult("{\"summary\": \"가상 결과 요약\"}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXAMINATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("검사가 완료되지 않았으면 409 EXAMINATION_NOT_COMPLETED")
    void notCompleted() throws Exception {
        givenServiceThrows(ErrorCode.EXAMINATION_NOT_COMPLETED);

        postResult("{\"summary\": \"가상 결과 요약\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAMINATION_NOT_COMPLETED"));
    }

    @Test
    @DisplayName("이미 결과가 있으면 409 EXAMINATION_RESULT_ALREADY_EXISTS")
    void alreadyExists() throws Exception {
        givenServiceThrows(ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);

        postResult("{\"summary\": \"가상 결과 요약\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXAMINATION_RESULT_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("검사 ID가 UUID 형식이 아니면 400 INVALID_INPUT")
    void invalidExaminationId() throws Exception {
        mockMvc.perform(post(URL, "not-a-uuid")
                        .header(INSTITUTION_HEADER, institutionId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"summary\": \"가상 결과 요약\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("검사 상태 변경 권한이 없으면 403을 반환한다")
    void changeStatusAccessDenied() throws Exception {

        given(examinationService.changeStatus(
                examinationId,
                institutionId,
                RoleType.EXAMINER,
                ExaminationStatus.IN_PROGRESS
        )).willThrow(
                new BaseException(ErrorCode.EXAMINATION_ACCESS_DENIED)
        );

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code")
                        .value("EXAMINATION_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("존재하지 않는 검사의 상태 변경은 404를 반환한다")
    void changeStatusNotFound() throws Exception {

        given(examinationService.changeStatus(
                examinationId,
                institutionId,
                RoleType.EXAMINER,
                ExaminationStatus.IN_PROGRESS
        )).willThrow(
                new BaseException(ErrorCode.EXAMINATION_NOT_FOUND)
        );

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code")
                        .value("EXAMINATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("허용되지 않은 검사 상태 전환은 409를 반환한다")
    void changeStatusInvalidTransition() throws Exception {

        given(examinationService.changeStatus(
                examinationId,
                institutionId,
                RoleType.EXAMINER,
                ExaminationStatus.COMPLETED
        )).willThrow(
                new BaseException(
                        ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION
                )
        );

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "COMPLETED"}
                                    """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("INVALID_EXAMINATION_STATUS_TRANSITION"));
    }

    @Test
    @DisplayName("유효하지 않은 예약으로 검사 시작 시 409를 반환한다")
    void changeStatusStartNotAllowed() throws Exception {

        given(examinationService.changeStatus(
                examinationId,
                institutionId,
                RoleType.EXAMINER,
                ExaminationStatus.IN_PROGRESS
        )).willThrow(
                new BaseException(ErrorCode.EXAMINATION_START_NOT_ALLOWED)
        );

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("EXAMINATION_START_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("status가 누락되면 400 VALIDATION_FAILED를 반환한다")
    void changeStatusMissingStatus() throws Exception {

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        then(examinationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("존재하지 않는 검사 상태값은 400 INVALID_INPUT을 반환한다")
    void changeStatusInvalidStatus() throws Exception {

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "STARTED"}
                                    """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        then(examinationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("검사 ID가 UUID 형식이 아니면 400 INVALID_INPUT을 반환한다")
    void changeStatusInvalidExaminationId() throws Exception {

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", "not-a-uuid")
                                .header("X-Institution-Id", institutionId.toString())
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        then(examinationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("X-Institution-Id 누락 시 400 MISSING_HEADER를 반환한다")
    void changeStatusMissingInstitutionHeader() throws Exception {

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Role", "EXAMINER")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        then(examinationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("X-Role 누락 시 400 MISSING_HEADER를 반환한다")
    void changeStatusMissingRoleHeader() throws Exception {

        mockMvc.perform(
                        patch("/api/v1/examinations/{examinationId}/status", examinationId)
                                .header("X-Institution-Id", institutionId.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                    {"status": "IN_PROGRESS"}
                                    """)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));

        then(examinationService).shouldHaveNoInteractions();
    }


}
