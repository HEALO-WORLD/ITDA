package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.ExaminationListResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ExaminationController.class)
@ActiveProfiles("test")
class ExaminationControllerTest {

    private static final String EXAMINATIONS_URL = "/api/v1/examinations";
    private static final String RESULT_URL = "/api/v1/examinations/{examinationId}/results";
    private static final String STATUS_URL = "/api/v1/examinations/{examinationId}/status";

    private static final String INSTITUTION_HEADER = "X-Institution-Id";
    private static final String ROLE_HEADER = "X-Role";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExaminationService examinationService;

    @MockitoBean
    private ExaminationResultService examinationResultService;

    // JPA Auditing으로 인해 필요한 메타모델을 Mock으로 대체
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID examinationId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();

    // ============================================================
    // 1. 검사기관별 검사 목록 조회 API
    // GET /api/v1/examinations
    // ============================================================

    @Nested
    @DisplayName("검사기관별 검사 목록 조회 API")
    class GetExaminationsTest {

        private ResultActions getExaminations() throws Exception {
            return mockMvc.perform(
                    get(EXAMINATIONS_URL)
                            .header(INSTITUTION_HEADER, institutionId.toString())
                            .header(ROLE_HEADER, "EXAMINER")
            );
        }

        private PageRequest pageable(int page, int size) {
            return PageRequest.of(
                    page,
                    size,
                    Sort.by(
                            Sort.Order.desc("createdAt"),
                            Sort.Order.desc("id")
                    )
            );
        }

        @Test
        @DisplayName("정상 조회 시 200 OK와 검사 목록을 반환한다")
        void getExaminationsSuccess() throws Exception {

            // given
            UUID referralId = UUID.randomUUID();
            UUID reservationId = UUID.randomUUID();

            ExaminationListResponse examination =
                    new ExaminationListResponse(
                            examinationId,
                            referralId,
                            reservationId,
                            ExaminationStatus.SCHEDULED,
                            null,
                            null
                    );

            given(examinationService.getExaminations(
                    institutionId,
                    RoleType.EXAMINER,
                    pageable(0, 10)
            )).willReturn(
                    PagedResponse.from(
                            new PageImpl<>(
                                    List.of(examination),
                                    pageable(0, 10),
                                    1
                            )
                    )
            );

            // when & then
            getExaminations()
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.contents.length()").value(1))
                    .andExpect(jsonPath("$.data.contents[0].examinationId")
                            .value(examinationId.toString()))
                    .andExpect(jsonPath("$.data.contents[0].referralId")
                            .value(referralId.toString()))
                    .andExpect(jsonPath("$.data.contents[0].reservationId")
                            .value(reservationId.toString()))
                    .andExpect(jsonPath("$.data.contents[0].status")
                            .value("SCHEDULED"))
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.totalPages").value(1))
                    .andExpect(jsonPath("$.data.size").value(10))
                    .andExpect(jsonPath("$.data.number").value(0));

            then(examinationService).should()
                    .getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable(0, 10)
                    );
        }

        @Test
        @DisplayName("페이지 파라미터가 없으면 기본값 0과 10을 사용한다")
        void getExaminationsDefaultPage() throws Exception {

            // given
            given(examinationService.getExaminations(
                    institutionId,
                    RoleType.EXAMINER,
                    pageable(0, 10)
            )).willReturn(
                    PagedResponse.from(
                            new PageImpl<>(
                                    List.of(),
                                    pageable(0, 10),
                                    0
                            )
                    )
            );

            // when & then
            getExaminations()
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contents").isEmpty())
                    .andExpect(jsonPath("$.data.totalElements").value(0))
                    .andExpect(jsonPath("$.data.number").value(0))
                    .andExpect(jsonPath("$.data.size").value(10));

            then(examinationService).should()
                    .getExaminations(
                            institutionId,
                            RoleType.EXAMINER,
                            pageable(0, 10)
                    );
        }

        @Test
        @DisplayName("EXAMINER가 아닌 사용자는 403을 반환한다")
        void getExaminationsAccessDenied() throws Exception {

            // given
            given(examinationService.getExaminations(
                    institutionId,
                    RoleType.REQUESTER,
                    pageable(0, 10)
            )).willThrow(
                    new BaseException(ErrorCode.EXAMINATION_ACCESS_DENIED)
            );

            // when & then
            mockMvc.perform(
                            get(EXAMINATIONS_URL)
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .header(ROLE_HEADER, "REQUESTER")
                    )
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_ACCESS_DENIED"));
        }

        @Test
        @DisplayName("기관 ID 헤더가 없으면 400 MISSING_HEADER를 반환한다")
        void getExaminationsMissingInstitutionHeader() throws Exception {

            // when & then
            mockMvc.perform(
                            get(EXAMINATIONS_URL)
                                    .header(ROLE_HEADER, "EXAMINER")
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("역할 헤더가 없으면 400 MISSING_HEADER를 반환한다")
        void getExaminationsMissingRoleHeader() throws Exception {

            // when & then
            mockMvc.perform(
                            get(EXAMINATIONS_URL)
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("음수 페이지 번호는 400을 반환한다")
        void getExaminationsInvalidPage() throws Exception {

            // when & then
            mockMvc.perform(
                            get(EXAMINATIONS_URL)
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .header(ROLE_HEADER, "EXAMINER")
                                    .param("page", "-1")
                    )
                    .andExpect(status().isBadRequest());

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("페이지 크기가 100을 초과하면 400을 반환한다")
        void getExaminationsInvalidSize() throws Exception {

            // when & then
            mockMvc.perform(
                            get(EXAMINATIONS_URL)
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .header(ROLE_HEADER, "EXAMINER")
                                    .param("size", "101")
                    )
                    .andExpect(status().isBadRequest());

            then(examinationService).shouldHaveNoInteractions();
        }
    }

    // ============================================================
    // 2. 검사 결과 등록 API
    // POST /api/v1/examinations/{examinationId}/results
    // ============================================================

    @Nested
    @DisplayName("검사 결과 등록 API")
    class RegisterResultTest {

        private ResultActions postResult(String body) throws Exception {
            return mockMvc.perform(
                    post(RESULT_URL, examinationId)
                            .header(INSTITUTION_HEADER, institutionId.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
            );
        }

        private void givenServiceThrows(ErrorCode errorCode) {
            given(examinationResultService.register(
                    any(), any(), any(), any()
            )).willThrow(new BaseException(errorCode));
        }

        @Test
        @DisplayName("정상 등록 시 201 Created와 결과 정보를 반환한다")
        void register() throws Exception {

            // given
            UUID resultId = UUID.randomUUID();

            given(examinationResultService.register(
                    examinationId,
                    institutionId,
                    "가상 결과 요약",
                    "가상 추가 소견"
            )).willReturn(
                    new ExaminationResultCreateResponse(
                            resultId,
                            examinationId,
                            LocalDateTime.of(2026, 10, 30, 14, 30)
                    )
            );

            // when & then
            postResult("""
                    {
                        "summary": "가상 결과 요약",
                        "notes": "가상 추가 소견"
                    }
                    """)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.resultId")
                            .value(resultId.toString()))
                    .andExpect(jsonPath("$.data.examinationId")
                            .value(examinationId.toString()))
                    .andExpect(jsonPath("$.data.registeredAt").exists());

            then(examinationResultService).should()
                    .register(
                            examinationId,
                            institutionId,
                            "가상 결과 요약",
                            "가상 추가 소견"
                    );
        }

        @Test
        @DisplayName("notes 없이도 검사 결과를 등록할 수 있다")
        void registerWithoutNotes() throws Exception {

            // given
            given(examinationResultService.register(
                    examinationId,
                    institutionId,
                    "가상 결과 요약",
                    null
            )).willReturn(
                    new ExaminationResultCreateResponse(
                            UUID.randomUUID(),
                            examinationId,
                            LocalDateTime.of(2026, 10, 30, 14, 30)
                    )
            );

            // when & then
            postResult("""
                    {"summary": "가상 결과 요약"}
                    """)
                    .andExpect(status().isCreated());
        }

        @ParameterizedTest(name = "[{index}] 요청 본문: {0}")
        @ValueSource(strings = {
                "{}",
                "{\"summary\": \"\"}",
                "{\"summary\": \"   \"}",
                "{\"summary\": null}"
        })
        @DisplayName("summary가 없거나 공백이면 400 VALIDATION_FAILED를 반환한다")
        void summaryRequired(String body) throws Exception {

            // when & then
            postResult(body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("VALIDATION_FAILED"));

            then(examinationResultService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("기관 ID 헤더가 없으면 400 MISSING_HEADER를 반환한다")
        void institutionHeaderRequired() throws Exception {

            // when & then
            mockMvc.perform(
                            post(RESULT_URL, examinationId)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"summary": "가상 결과 요약"}
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationResultService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 검사기관의 결과 등록 요청은 403을 반환한다")
        void otherInstitution() throws Exception {

            // given
            givenServiceThrows(
                    ErrorCode.EXAMINATION_RESULT_ACCESS_DENIED
            );

            // when & then
            postResult("""
                    {"summary": "가상 결과 요약"}
                    """)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_RESULT_ACCESS_DENIED"));
        }

        @Test
        @DisplayName("검사가 존재하지 않으면 404를 반환한다")
        void notFound() throws Exception {

            // given
            givenServiceThrows(ErrorCode.EXAMINATION_NOT_FOUND);

            // when & then
            postResult("""
                    {"summary": "가상 결과 요약"}
                    """)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("검사가 완료되지 않았으면 409를 반환한다")
        void notCompleted() throws Exception {

            // given
            givenServiceThrows(ErrorCode.EXAMINATION_NOT_COMPLETED);

            // when & then
            postResult("""
                    {"summary": "가상 결과 요약"}
                    """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_NOT_COMPLETED"));
        }

        @Test
        @DisplayName("검사 결과가 이미 등록되어 있으면 409를 반환한다")
        void alreadyExists() throws Exception {

            // given
            givenServiceThrows(
                    ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS
            );

            // when & then
            postResult("""
                    {"summary": "가상 결과 요약"}
                    """)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_RESULT_ALREADY_EXISTS"));
        }

        @Test
        @DisplayName("검사 ID가 UUID 형식이 아니면 400 INVALID_INPUT을 반환한다")
        void invalidExaminationId() throws Exception {

            // when & then
            mockMvc.perform(
                            post(RESULT_URL, "not-a-uuid")
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"summary": "가상 결과 요약"}
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_INPUT"));

            then(examinationResultService).shouldHaveNoInteractions();
        }
    }

    // ============================================================
    // 3. 검사 결과 조회 API
    // GET /api/v1/examinations/{examinationId}/results
    // ============================================================

    @Nested
    @DisplayName("검사 결과 조회 API")
    class GetResultTest {

        private ResultActions getResult(String role) throws Exception {
            return mockMvc.perform(
                    get(RESULT_URL, examinationId)
                            .header(INSTITUTION_HEADER, institutionId.toString())
                            .header(ROLE_HEADER, role)
            );
        }

        private void givenGetResultThrows(
                RoleType role,
                ErrorCode errorCode
        ) {
            given(examinationResultService.getResultByExaminationId(
                    examinationId,
                    institutionId,
                    role
            )).willThrow(new BaseException(errorCode));
        }

        @Test
        @DisplayName("검사기관 담당자는 검사 ID로 결과를 정상 조회할 수 있다")
        void getResultSuccess() throws Exception {

            // given
            UUID resultId = UUID.randomUUID();
            UUID referralId = UUID.randomUUID();

            ExaminationResultResponse response =
                    new ExaminationResultResponse(
                            resultId,
                            examinationId,
                            referralId,
                            "CT 검사 결과 정상",
                            "특이 소견 없음",
                            LocalDateTime.of(2026, 11, 2, 15, 0)
                    );

            given(examinationResultService.getResultByExaminationId(
                    examinationId,
                    institutionId,
                    RoleType.EXAMINER
            )).willReturn(response);

            // when & then
            getResult("EXAMINER")
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

            then(examinationResultService).should()
                    .getResultByExaminationId(
                            examinationId,
                            institutionId,
                            RoleType.EXAMINER
                    );
        }

        @Test
        @DisplayName("다른 검사기관의 조회 요청은 403을 반환한다")
        void getResultOtherInstitutionDenied() throws Exception {

            // given
            givenGetResultThrows(
                    RoleType.EXAMINER,
                    ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
            );

            // when & then
            getResult("EXAMINER")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_RESULT_READ_ACCESS_DENIED"));
        }

        @Test
        @DisplayName("REQUESTER 역할의 결과 조회 요청은 403을 반환한다")
        void getResultRequesterDenied() throws Exception {

            // given
            givenGetResultThrows(
                    RoleType.REQUESTER,
                    ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
            );

            // when & then
            getResult("REQUESTER")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_RESULT_READ_ACCESS_DENIED"));
        }

        @Test
        @DisplayName("존재하지 않는 검사 조회는 404를 반환한다")
        void getResultExaminationNotFound() throws Exception {

            // given
            givenGetResultThrows(
                    RoleType.EXAMINER,
                    ErrorCode.EXAMINATION_NOT_FOUND
            );

            // when & then
            getResult("EXAMINER")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("검사 결과가 등록되지 않았다면 404를 반환한다")
        void getResultNotFound() throws Exception {

            // given
            givenGetResultThrows(
                    RoleType.EXAMINER,
                    ErrorCode.EXAMINATION_RESULT_NOT_FOUND
            );

            // when & then
            getResult("EXAMINER")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_RESULT_NOT_FOUND"));
        }

        @Test
        @DisplayName("기관 ID 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
        void getResultMissingInstitutionHeader() throws Exception {

            // when & then
            mockMvc.perform(
                            get(RESULT_URL, examinationId)
                                    .header(ROLE_HEADER, "EXAMINER")
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationResultService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("역할 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
        void getResultMissingRoleHeader() throws Exception {

            // when & then
            mockMvc.perform(
                            get(RESULT_URL, examinationId)
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationResultService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("잘못된 검사 ID는 400 INVALID_INPUT을 반환한다")
        void getResultInvalidExaminationId() throws Exception {

            // when & then
            mockMvc.perform(
                            get(RESULT_URL, "not-a-uuid")
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .header(ROLE_HEADER, "EXAMINER")
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_INPUT"));

            then(examinationResultService).shouldHaveNoInteractions();
        }
    }

    // ============================================================
    // 4. 검사 상태 변경 API
    // PATCH /api/v1/examinations/{examinationId}/status
    // ============================================================

    @Nested
    @DisplayName("검사 상태 변경 API")
    class ChangeStatusTest {

        private ResultActions patchStatus(String statusValue) throws Exception {
            return mockMvc.perform(
                    patchRequest(examinationId)
                            .content("""
                                    {"status": "%s"}
                                    """.formatted(statusValue))
            );
        }

        private MockHttpServletRequestBuilder patchRequest(UUID id) {
            return patch(STATUS_URL, id)
                    .header(INSTITUTION_HEADER, institutionId.toString())
                    .header(ROLE_HEADER, "EXAMINER")
                    .contentType(MediaType.APPLICATION_JSON);
        }

        private void givenChangeStatusThrows(
                ExaminationStatus status,
                ErrorCode errorCode
        ) {
            given(examinationService.changeStatus(
                    examinationId,
                    institutionId,
                    RoleType.EXAMINER,
                    status
            )).willThrow(new BaseException(errorCode));
        }

        @Test
        @DisplayName("검사 시작 요청 시 200 OK와 IN_PROGRESS 상태를 반환한다")
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
            patchStatus("IN_PROGRESS")
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
            patchStatus("COMPLETED")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.previousStatus")
                            .value("IN_PROGRESS"))
                    .andExpect(jsonPath("$.data.currentStatus")
                            .value("COMPLETED"));
        }

        @Test
        @DisplayName("검사 상태 변경 권한이 없으면 403을 반환한다")
        void changeStatusAccessDenied() throws Exception {

            // given
            givenChangeStatusThrows(
                    ExaminationStatus.IN_PROGRESS,
                    ErrorCode.EXAMINATION_ACCESS_DENIED
            );

            // when & then
            patchStatus("IN_PROGRESS")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_ACCESS_DENIED"));
        }

        @Test
        @DisplayName("존재하지 않는 검사의 상태 변경은 404를 반환한다")
        void changeStatusNotFound() throws Exception {

            // given
            givenChangeStatusThrows(
                    ExaminationStatus.IN_PROGRESS,
                    ErrorCode.EXAMINATION_NOT_FOUND
            );

            // when & then
            patchStatus("IN_PROGRESS")
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_NOT_FOUND"));
        }

        @Test
        @DisplayName("허용되지 않은 검사 상태 전환은 409를 반환한다")
        void changeStatusInvalidTransition() throws Exception {

            // given
            givenChangeStatusThrows(
                    ExaminationStatus.COMPLETED,
                    ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION
            );

            // when & then
            patchStatus("COMPLETED")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_EXAMINATION_STATUS_TRANSITION"));
        }

        @Test
        @DisplayName("유효하지 않은 예약으로 검사 시작 시 409를 반환한다")
        void changeStatusStartNotAllowed() throws Exception {

            // given
            givenChangeStatusThrows(
                    ExaminationStatus.IN_PROGRESS,
                    ErrorCode.EXAMINATION_START_NOT_ALLOWED
            );

            // when & then
            patchStatus("IN_PROGRESS")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code")
                            .value("EXAMINATION_START_NOT_ALLOWED"));
        }

        @Test
        @DisplayName("status가 누락되면 400 VALIDATION_FAILED를 반환한다")
        void changeStatusMissingStatus() throws Exception {

            // when & then
            mockMvc.perform(
                            patchRequest(examinationId)
                                    .content("{}")
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("VALIDATION_FAILED"));

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("존재하지 않는 검사 상태값은 400 INVALID_INPUT을 반환한다")
        void changeStatusInvalidStatus() throws Exception {

            // when & then
            patchStatus("STARTED")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_INPUT"));

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("검사 ID가 UUID 형식이 아니면 400 INVALID_INPUT을 반환한다")
        void changeStatusInvalidExaminationId() throws Exception {

            // when & then
            mockMvc.perform(
                            patch(STATUS_URL, "not-a-uuid")
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .header(ROLE_HEADER, "EXAMINER")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"status": "IN_PROGRESS"}
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("INVALID_INPUT"));

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("기관 ID 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
        void changeStatusMissingInstitutionHeader() throws Exception {

            // when & then
            mockMvc.perform(
                            patch(STATUS_URL, examinationId)
                                    .header(ROLE_HEADER, "EXAMINER")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"status": "IN_PROGRESS"}
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationService).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("역할 헤더가 누락되면 400 MISSING_HEADER를 반환한다")
        void changeStatusMissingRoleHeader() throws Exception {

            // when & then
            mockMvc.perform(
                            patch(STATUS_URL, examinationId)
                                    .header(INSTITUTION_HEADER, institutionId.toString())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {"status": "IN_PROGRESS"}
                                            """)
                    )
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code")
                            .value("MISSING_HEADER"));

            then(examinationService).shouldHaveNoInteractions();
        }
    }
}