package healoworld.referral_service.global.deadletter;

import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DeadLetterAdminController 웹 계층 테스트.
 * <p>
 * DLT 실패 메시지 목록·상세·재처리·무시 API의 요청 바인딩, 헤더 누락·형식 오류, 업무 예외의 상태 코드 변환, 응답 형식을 확인한다.
 * 역할 확인(ADMIN만 허용)은 Service가 하므로(DeadLetterServiceTest) 여기서는 X-Role을 그대로 넘기는지와 403 변환만 본다.
 */
@WebMvcTest(DeadLetterAdminController.class)
@ActiveProfiles("test")
class DeadLetterAdminControllerTest {

    private static final String BASE = "/api/v1/referral-dead-letters";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeadLetterService deadLetterService;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    private final UUID adminId = UUID.randomUUID();
    private final UUID deadLetterId = UUID.randomUUID();
    private final String eventId = UUID.randomUUID().toString();
    private final LocalDateTime createdAt = LocalDateTime.of(2026, 11, 11, 9, 0);

    // ==============================
    // GET 목록
    // ==============================

    @Nested
    @DisplayName("목록 GET /api/v1/referral-dead-letters")
    class List_ {

        @Test
        @DisplayName("status·page·size가 검색 조건으로 바인딩되고, 역할을 Service에 넘겨 PagedResponse로 200을 돌려준다")
        void bindsConditionAndPages() throws Exception {
            given(deadLetterService.getDeadLetters(eq(RoleType.ADMIN), any(DeadLetterSearchCondition.class)))
                    .willReturn(new PagedResponse<>(List.of(summary(DeadLetterStatus.PENDING)), 31, 2, 30, 1));

            mockMvc.perform(get(BASE)
                            .header("X-Role", "ADMIN")
                            .param("status", "PENDING")
                            .param("page", "1")
                            .param("size", "30"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("실패 메시지 목록 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.contents[0].deadLetterId").value(deadLetterId.toString()))
                    .andExpect(jsonPath("$.data.contents[0].originalTopic").value("slot-results"))
                    .andExpect(jsonPath("$.data.contents[0].originalPartition").value(2))
                    .andExpect(jsonPath("$.data.contents[0].originalOffset").value(1234))
                    .andExpect(jsonPath("$.data.contents[0].eventId").value(eventId))
                    .andExpect(jsonPath("$.data.contents[0].eventType").value("SlotHeld"))
                    .andExpect(jsonPath("$.data.contents[0].status").value("PENDING"))
                    // 목록에는 key·payload를 내리지 않는다 (상세에서만)
                    .andExpect(jsonPath("$.data.contents[0].payload").doesNotExist())
                    .andExpect(jsonPath("$.data.contents[0].messageKey").doesNotExist())
                    .andExpect(jsonPath("$.data.totalElements").value(31))
                    .andExpect(jsonPath("$.data.number").value(1));

            DeadLetterSearchCondition cond = capturedCondition();
            assertThat(cond.status()).isEqualTo(DeadLetterStatus.PENDING);
            assertThat(cond.toPageable().getPageNumber()).isEqualTo(1);
            assertThat(cond.toPageable().getPageSize()).isEqualTo(30);
            assertThat(cond.toPageable().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        @Test
        @DisplayName("status를 생략하면 null(전체)로 넘기고, 허용되지 않은 size는 10으로 바뀐다")
        void defaults() throws Exception {
            given(deadLetterService.getDeadLetters(any(), any())).willReturn(new PagedResponse<>(List.of(), 0, 0, 10, 0));

            mockMvc.perform(get(BASE).header("X-Role", "ADMIN").param("size", "999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.contents").isEmpty());

            DeadLetterSearchCondition cond = capturedCondition();
            assertThat(cond.status()).isNull();
            assertThat(cond.toPageable().getPageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("정의되지 않은 처리 상태로 필터하면 400 VALIDATION_FAILED이고 Service를 호출하지 않는다")
        void invalidStatus() throws Exception {
            mockMvc.perform(get(BASE).header("X-Role", "ADMIN").param("status", "DONE"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

            verifyNoInteractions(deadLetterService);
        }

        @Test
        @DisplayName("X-Role이 없으면 400 MISSING_HEADER, Auth 명세에 없는 역할 값이면 400 INVALID_INPUT")
        void roleHeader() throws Exception {
            mockMvc.perform(get(BASE))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
            mockMvc.perform(get(BASE).header("X-Role", "USER"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(deadLetterService);
        }

        @Test
        @DisplayName("ADMIN이 아니어서 Service가 거부하면 403 ADMIN_ONLY")
        void notAdmin() throws Exception {
            given(deadLetterService.getDeadLetters(eq(RoleType.EXAMINER), any()))
                    .willThrow(new BaseException(ErrorCode.ADMIN_ONLY));

            mockMvc.perform(get(BASE).header("X-Role", "EXAMINER"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ADMIN_ONLY"));
        }

        private DeadLetterSearchCondition capturedCondition() {
            ArgumentCaptor<DeadLetterSearchCondition> captor = ArgumentCaptor.forClass(DeadLetterSearchCondition.class);
            then(deadLetterService).should().getDeadLetters(eq(RoleType.ADMIN), captor.capture());
            return captor.getValue();
        }
    }

    // ==============================
    // GET 상세
    // ==============================

    @Nested
    @DisplayName("상세 GET /api/v1/referral-dead-letters/{deadLetterId}")
    class Detail {

        @Test
        @DisplayName("200과 key·payload 원문을 포함한 상세를 돌려준다")
        void ok() throws Exception {
            given(deadLetterService.getDeadLetter(RoleType.ADMIN, deadLetterId))
                    .willReturn(detail(DeadLetterStatus.PENDING, null));

            mockMvc.perform(get(BASE + "/{deadLetterId}", deadLetterId).header("X-Role", "ADMIN"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("실패 메시지 조회가 완료되었습니다."))
                    .andExpect(jsonPath("$.data.deadLetterId").value(deadLetterId.toString()))
                    .andExpect(jsonPath("$.data.messageKey").value("slot-key"))
                    .andExpect(jsonPath("$.data.payload").value("{\"reservationId\":null}"))
                    .andExpect(jsonPath("$.data.exceptionClass").value("java.lang.IllegalArgumentException"))
                    .andExpect(jsonPath("$.data.failureSummary").value("reservationId 누락"))
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.resolvedBy").doesNotExist());
        }

        @Test
        @DisplayName("없는 실패 메시지면 404 DEAD_LETTER_NOT_FOUND")
        void notFound() throws Exception {
            given(deadLetterService.getDeadLetter(any(), any()))
                    .willThrow(new BaseException(ErrorCode.DEAD_LETTER_NOT_FOUND));

            mockMvc.perform(get(BASE + "/{deadLetterId}", deadLetterId).header("X-Role", "ADMIN"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("DEAD_LETTER_NOT_FOUND"));
        }

        @Test
        @DisplayName("ID가 UUID 형식이 아니면 400 INVALID_INPUT")
        void invalidId() throws Exception {
            mockMvc.perform(get(BASE + "/{deadLetterId}", "not-a-uuid").header("X-Role", "ADMIN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

            verifyNoInteractions(deadLetterService);
        }
    }

    // ==============================
    // POST 재처리·무시
    // ==============================

    @Nested
    @DisplayName("재처리·무시 POST /api/v1/referral-dead-letters/{deadLetterId}/(reprocess|ignore)")
    class Resolve {

        @Test
        @DisplayName("재처리: 200과 REPROCESSED 상세를 돌려주고, 관리자 ID·역할·메시지 ID를 Service에 넘긴다")
        void reprocess() throws Exception {
            given(deadLetterService.reprocess(adminId, RoleType.ADMIN, deadLetterId))
                    .willReturn(detail(DeadLetterStatus.REPROCESSED, adminId));

            mockMvc.perform(resolveRequest("reprocess"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("실패 메시지를 원래 토픽으로 다시 발행했습니다."))
                    .andExpect(jsonPath("$.data.status").value("REPROCESSED"))
                    .andExpect(jsonPath("$.data.resolvedBy").value(adminId.toString()))
                    .andExpect(jsonPath("$.data.resolvedAt").exists());
        }

        @Test
        @DisplayName("무시: 200과 IGNORED 상세를 돌려주고, 관리자 ID·역할·메시지 ID를 Service에 넘긴다")
        void ignore() throws Exception {
            given(deadLetterService.ignore(adminId, RoleType.ADMIN, deadLetterId))
                    .willReturn(detail(DeadLetterStatus.IGNORED, adminId));

            mockMvc.perform(resolveRequest("ignore"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("실패 메시지를 무시 처리했습니다."))
                    .andExpect(jsonPath("$.data.status").value("IGNORED"))
                    .andExpect(jsonPath("$.data.resolvedBy").value(adminId.toString()));
        }

        @ParameterizedTest(name = "재처리 {0}")
        @EnumSource(value = ErrorCode.class, names = {
                "ADMIN_ONLY", "DEAD_LETTER_NOT_FOUND", "DEAD_LETTER_ALREADY_RESOLVED", "DEAD_LETTER_REPUBLISH_FAILED"})
        @DisplayName("재처리의 업무 예외는 ErrorCode의 상태 코드·코드·메시지로 응답한다 (403·404·409·503)")
        void reprocessErrors(ErrorCode errorCode) throws Exception {
            given(deadLetterService.reprocess(any(), any(), any())).willThrow(new BaseException(errorCode));

            mockMvc.perform(resolveRequest("reprocess"))
                    .andExpect(status().is(errorCode.getStatus().value()))
                    .andExpect(jsonPath("$.code").value(errorCode.name()))
                    .andExpect(jsonPath("$.message").value(errorCode.getMessage()));
        }

        @ParameterizedTest(name = "무시 {0}")
        @EnumSource(value = ErrorCode.class, names = {"ADMIN_ONLY", "DEAD_LETTER_NOT_FOUND", "DEAD_LETTER_ALREADY_RESOLVED"})
        @DisplayName("무시의 업무 예외는 ErrorCode의 상태 코드·코드로 응답한다 (403·404·409)")
        void ignoreErrors(ErrorCode errorCode) throws Exception {
            given(deadLetterService.ignore(any(), any(), any())).willThrow(new BaseException(errorCode));

            mockMvc.perform(resolveRequest("ignore"))
                    .andExpect(status().is(errorCode.getStatus().value()))
                    .andExpect(jsonPath("$.code").value(errorCode.name()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"reprocess", "ignore"})
        @DisplayName("처리한 관리자를 남겨야 하므로 X-User-Id가 없으면 400 MISSING_HEADER이고 처리하지 않는다")
        void missingUser(String action) throws Exception {
            mockMvc.perform(post(BASE + "/{deadLetterId}/" + action, deadLetterId).header("X-Role", "ADMIN"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MISSING_HEADER"))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X-User-Id")));

            verifyNoInteractions(deadLetterService);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"reprocess", "ignore"})
        @DisplayName("GET으로 요청하면 405 UNSUPPORTED_REQUEST이고 처리하지 않는다")
        void wrongMethod(String action) throws Exception {
            mockMvc.perform(get(BASE + "/{deadLetterId}/" + action, deadLetterId)
                            .header("X-User-Id", adminId.toString())
                            .header("X-Role", "ADMIN"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_REQUEST"));

            verifyNoInteractions(deadLetterService);
        }

        private MockHttpServletRequestBuilder resolveRequest(String action) {
            return post(BASE + "/{deadLetterId}/" + action, deadLetterId)
                    .header("X-User-Id", adminId.toString())
                    .header("X-Role", "ADMIN");
        }
    }

    // ==============================
    // 도우미
    // ==============================

    private DeadLetterResponse summary(DeadLetterStatus status) {
        return new DeadLetterResponse(deadLetterId, "slot-results", 2, 1234L, eventId, "SlotHeld",
                "java.lang.IllegalArgumentException", "reservationId 누락", status, createdAt, null);
    }

    private DeadLetterDetailResponse detail(DeadLetterStatus status, UUID resolvedBy) {
        LocalDateTime resolvedAt = resolvedBy == null ? null : createdAt.plusHours(1);
        return new DeadLetterDetailResponse(deadLetterId, "slot-results", 2, 1234L, "slot-key", eventId, "SlotHeld",
                "{\"reservationId\":null}", "java.lang.IllegalArgumentException", "reservationId 누락", status,
                createdAt, resolvedAt, resolvedBy);
    }
}
