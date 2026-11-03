package healoworld.slot_service.presentation;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.controller.SlotAdminController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlotAdminController.class)
class SlotAdminControllerTest {

    private static final String ROLE_HEADER = "X-Role";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlotReleaseService slotReleaseService;

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스 테스트에서도 JPA 메타모델을 요구한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void ADMIN이_강제_해제하면_200과_AVAILABLE을_반환한다() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        given(slotReleaseService.forceRelease(slotId, reservationId)).willReturn(ReleaseOutcome.RELEASED);

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", slotId)
                        .header(ROLE_HEADER, "ADMIN")
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
        given(slotReleaseService.forceRelease(slotId, reservationId)).willReturn(ReleaseOutcome.DETACHED);

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", slotId)
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.result").value("DETACHED"))
                .andExpect(jsonPath("$.data.status").value("UNAVAILABLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "EXAMINER", "admin"})
    void ADMIN이_아니면_403이고_서비스를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_ONLY.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any());
    }

    @Test
    void 권한_헤더가_없으면_400_MISSING_HEADER다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_HEADER.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any());
    }

    @Test
    void reservationId가_없으면_400이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verify(slotReleaseService, never()).forceRelease(any(), any());
    }

    @Test
    void 해제할_대상이_아니면_409_SLOT_NOT_RELEASABLE이다() throws Exception {
        given(slotReleaseService.forceRelease(any(), any()))
                .willThrow(new BaseException(ErrorCode.SLOT_NOT_RELEASABLE));

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_RELEASABLE.name()));
    }

    @Test
    void 없는_슬롯이면_404_SLOT_NOT_FOUND다() throws Exception {
        given(slotReleaseService.forceRelease(any(), any()))
                .willThrow(new BaseException(ErrorCode.SLOT_NOT_FOUND));

        mockMvc.perform(post("/api/v1/slots/{slotId}/release", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_FOUND.name()));
    }

    private String body(UUID reservationId) {
        return """
                {"reservationId": "%s"}
                """.formatted(reservationId);
    }
}
