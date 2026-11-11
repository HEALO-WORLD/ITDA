package healoworld.slot_service.presentation;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.application.service.SlotStatusHistoryService;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.dto.PagedResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.controller.SlotAdminController;
import healoworld.slot_service.presentation.dto.response.SlotStatusHistoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlotAdminController.class)
class SlotAdminControllerTest {

    private static final String ROLE_HEADER = "X-Role";
    private static final String USER_ID_HEADER = "X-User-Id";
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlotReleaseService slotReleaseService;

    @MockitoBean
    private SlotStatusHistoryService slotStatusHistoryService;

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스 테스트에서도 JPA 메타모델을 요구한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void ADMIN이_강제_해제하면_200과_AVAILABLE을_반환한다() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        given(slotReleaseService.forceRelease(slotId, reservationId, ADMIN_ID)).willReturn(ReleaseOutcome.RELEASED);

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", slotId)
                        .header(ROLE_HEADER, "ADMIN")
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.slotId").value(slotId.toString()))
                .andExpect(jsonPath("$.data.releasedReservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.data.result").value("RELEASED"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
    }

    @Test
    void UNAVAILABLE_슬롯의_예약_연결만_끊으면_DETACHED와_UNAVAILABLE을_반환한다() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        given(slotReleaseService.forceRelease(slotId, reservationId, ADMIN_ID)).willReturn(ReleaseOutcome.DETACHED);

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", slotId)
                        .header(ROLE_HEADER, "ADMIN")
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DETACHED"))
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "EXAMINER"})
    void ADMIN이_아니면_403이고_서비스를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, role)
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_ONLY.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "UNKNOWN"})
    void 정의되지_않은_권한_값이면_400_INVALID_INPUT이고_서비스를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, role)
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any(), any());
    }

    @Test
    void 권한_헤더가_없으면_400_MISSING_HEADER다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_HEADER.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any(), any());
    }

    @Test
    void 사용자_ID_헤더가_없으면_400_MISSING_HEADER다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_HEADER.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any(), any());
    }

    @Test
    void reservationId가_없으면_400이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any(), any());
    }

    @Test
    void 해제할_대상이_아니면_409_SLOT_NOT_RELEASABLE이다() throws Exception {
        given(slotReleaseService.forceRelease(any(), any(), any()))
                .willThrow(new BaseException(ErrorCode.SLOT_NOT_RELEASABLE));

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_RELEASABLE.name()));
    }

    @Test
    void 없는_슬롯이면_404_SLOT_NOT_FOUND다() throws Exception {
        given(slotReleaseService.forceRelease(any(), any(), any()))
                .willThrow(new BaseException(ErrorCode.SLOT_NOT_FOUND));

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .header(USER_ID_HEADER, ADMIN_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_FOUND.name()));
    }

    // ---------- 슬롯 상태 이력 조회 ----------

    @Test
    void 이력조회_ADMIN이면_200과_페이지를_반환한다() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        SlotStatusHistoryResponse item = new SlotStatusHistoryResponse(
                UUID.randomUUID(), slotId, reservationId, SlotStatus.AVAILABLE, SlotStatus.HELD,
                SlotStatusChangeReason.HOLD, eventId, LocalDateTime.of(2026, 10, 30, 10, 0), null);
        given(slotStatusHistoryService.getHistories(slotId, 1, 5))
                .willReturn(new PagedResponse<>(List.of(item), 6, 2, 5, 1));

        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", slotId)
                        .header(ROLE_HEADER, "ADMIN")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.contents[0].slotId").value(slotId.toString()))
                .andExpect(jsonPath("$.data.contents[0].reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.data.contents[0].fromStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.data.contents[0].toStatus").value("HELD"))
                .andExpect(jsonPath("$.data.contents[0].reason").value("HOLD"))
                .andExpect(jsonPath("$.data.contents[0].sourceEventId").value(eventId.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(6))
                .andExpect(jsonPath("$.data.number").value(1));
    }

    @Test
    void 이력조회_페이지_파라미터가_없으면_0페이지_20개로_조회한다() throws Exception {
        UUID slotId = UUID.randomUUID();
        given(slotStatusHistoryService.getHistories(slotId, 0, 20))
                .willReturn(new PagedResponse<>(List.of(), 0, 0, 20, 0));

        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", slotId)
                        .header(ROLE_HEADER, "ADMIN"))
                .andExpect(status().isOk());

        verify(slotStatusHistoryService).getHistories(slotId, 0, 20);
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "EXAMINER"})
    void 이력조회_ADMIN이_아니면_403이고_서비스를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", UUID.randomUUID())
                        .header(ROLE_HEADER, role))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_ONLY.name()));

        verify(slotStatusHistoryService, never()).getHistories(any(), anyInt(), anyInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "UNKNOWN"})
    void 이력조회_정의되지_않은_권한_값이면_400_INVALID_INPUT이고_서비스를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", UUID.randomUUID())
                        .header(ROLE_HEADER, role))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));

        verify(slotStatusHistoryService, never()).getHistories(any(), anyInt(), anyInt());
    }

    @Test
    void 이력조회_권한_헤더가_없으면_400_MISSING_HEADER다() throws Exception {
        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_HEADER.name()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=-1", "size=0", "size=101"})
    void 이력조회_페이지_범위를_벗어나면_400_VALIDATION_FAILED다(String query) throws Exception {
        String[] pair = query.split("=");

        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .param(pair[0], pair[1]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verify(slotStatusHistoryService, never()).getHistories(any(), anyInt(), anyInt());
    }

    @Test
    void 이력조회_슬롯_ID가_UUID가_아니면_400_INVALID_INPUT이다() throws Exception {
        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", "not-a-uuid")
                        .header(ROLE_HEADER, "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_INPUT.name()));
    }

    @Test
    void 이력조회_없는_슬롯이면_404_SLOT_NOT_FOUND다() throws Exception {
        given(slotStatusHistoryService.getHistories(any(), anyInt(), anyInt()))
                .willThrow(new BaseException(ErrorCode.SLOT_NOT_FOUND));

        mockMvc.perform(get("/api/v1/slots/{slotId}/status-histories", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_FOUND.name()));
    }

    private String body(UUID reservationId) {
        return """
                {"reservationId": "%s"}
                """.formatted(reservationId);
    }
}
