package healoworld.slot_service.presentation;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.controller.SlotHoldController;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SlotHoldController.class)
class SlotHoldControllerTest {

    private static final String ROLE_HEADER = "X-Role";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlotHoldService slotHoldService;

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스 테스트에서도 JPA 메타모델을 요구한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void ADMIN이_HOLD에_성공하면_200과_만료시각을_반환한다() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = LocalDateTime.of(2026, 11, 2, 9, 5);
        given(slotHoldService.hold(slotId, reservationId))
                .willReturn(HoldResult.success(slotId, reservationId, heldUntil));

        mockMvc.perform(post("/api/v1/slots/{slotId}/hold", slotId)
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reservationId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.slotId").value(slotId.toString()))
                .andExpect(jsonPath("$.data.reservationId").value(reservationId.toString()))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.heldUntil").value("2026-11-02T09:05:00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "EXAMINER", "admin"})
    void ADMIN이_아니면_403이고_서비스를_호출하지_않는다(String role) throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/hold", UUID.randomUUID())
                        .header(ROLE_HEADER, role)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ADMIN_ONLY.name()));

        verify(slotHoldService, never()).hold(any(), any());
    }

    @Test
    void 권한_헤더가_없으면_400_MISSING_HEADER다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/hold", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.MISSING_HEADER.name()));

        verify(slotHoldService, never()).hold(any(), any());
    }

    @Test
    void HOLD에_실패하면_409_SLOT_NOT_AVAILABLE이다() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        given(slotHoldService.hold(slotId, reservationId))
                .willReturn(HoldResult.failed(slotId, reservationId, HoldFailureReason.SLOT_CONFLICT));

        mockMvc.perform(post("/api/v1/slots/{slotId}/hold", slotId)
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(reservationId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_AVAILABLE.name()));
    }

    @Test
    void 없는_슬롯이면_404_SLOT_NOT_FOUND다() throws Exception {
        given(slotHoldService.hold(any(), any()))
                .willThrow(new BaseException(ErrorCode.SLOT_NOT_FOUND));

        mockMvc.perform(post("/api/v1/slots/{slotId}/hold", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SLOT_NOT_FOUND.name()));
    }

    @Test
    void reservationId가_없으면_400이고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/v1/slots/{slotId}/hold", UUID.randomUUID())
                        .header(ROLE_HEADER, "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));

        verify(slotHoldService, never()).hold(any(), any());
    }

    private String body(UUID reservationId) {
        return """
                {"reservationId": "%s"}
                """.formatted(reservationId);
    }
}
