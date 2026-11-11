package healoworld.slot_service.presentation;

import healoworld.slot_service.application.service.SlotStatusQueryService;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.controller.SlotInternalController;
import healoworld.slot_service.presentation.dto.request.SlotStatusQueryRequest;
import healoworld.slot_service.presentation.dto.response.SlotReservationContextResponse;
import healoworld.slot_service.presentation.dto.response.SlotStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 내부 API의 HTTP 계약: Referral SlotClient가 그대로 읽는 JSON 배열 모양과 입력 검증
@WebMvcTest(SlotInternalController.class)
class SlotInternalControllerTest {

    private static final String URL = "/api/v1/internal/slots/statuses";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SlotStatusQueryService slotStatusQueryService;

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스 테스트에서도 JPA 메타모델을 요구한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void returnsReservationContextAsPlainObject() throws Exception {
        UUID slotId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID examTypeId = UUID.randomUUID();
        given(slotStatusQueryService.getReservationContext(slotId)).willReturn(
                new SlotReservationContextResponse(slotId, equipmentId, examTypeId,
                        LocalDate.of(2026, 11, 10), LocalTime.of(10, 0), LocalTime.of(11, 0)));

        mockMvc.perform(get("/api/v1/internal/slots/{slotId}", slotId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slotId").value(slotId.toString()))
                .andExpect(jsonPath("$.equipmentId").value(equipmentId.toString()))
                .andExpect(jsonPath("$.examTypeId").value(examTypeId.toString()))
                .andExpect(jsonPath("$.date").value("2026-11-10"))
                .andExpect(jsonPath("$.startTime").value("10:00:00"))
                .andExpect(jsonPath("$.endTime").value("11:00:00"))
                .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    void returnsNotFoundForMissingReservationContext() throws Exception {
        UUID slotId = UUID.randomUUID();
        given(slotStatusQueryService.getReservationContext(slotId))
                .willThrow(new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/internal/slots/{slotId}", slotId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("응답은 ApiResponse로 감싸지 않은 배열이고, 각 항목은 slotId·status·reservationId·heldUntil이다")
    void returnsPlainArrayMatchingReferralContract() throws Exception {
        UUID heldSlot = UUID.randomUUID();
        UUID heldReservation = UUID.randomUUID();
        UUID availableSlot = UUID.randomUUID();
        given(slotStatusQueryService.getStatuses(List.of(heldSlot, availableSlot))).willReturn(List.of(
                new SlotStatusResponse(heldSlot, SlotStatus.HELD, heldReservation, LocalDateTime.of(2026, 11, 13, 10, 5)),
                new SlotStatusResponse(availableSlot, SlotStatus.AVAILABLE, null, null)));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slotIds\":[\"" + heldSlot + "\",\"" + availableSlot + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$.success").doesNotExist())
                .andExpect(jsonPath("$[0].slotId").value(heldSlot.toString()))
                .andExpect(jsonPath("$[0].status").value("HELD"))
                .andExpect(jsonPath("$[0].reservationId").value(heldReservation.toString()))
                // Referral은 LocalDateTime으로 읽으므로 시간대 없는 ISO 문자열이어야 한다
                .andExpect(jsonPath("$[0].heldUntil").value("2026-11-13T10:05:00"))
                .andExpect(jsonPath("$[1].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[1].reservationId").doesNotExist())
                .andExpect(jsonPath("$[1].heldUntil").doesNotExist());
    }

    @Test
    @DisplayName("조회 결과가 없으면 빈 배열이다")
    void emptyResult() throws Exception {
        given(slotStatusQueryService.getStatuses(any())).willReturn(List.of());

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"slotIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("slotIds가 없거나, null 항목이 있거나, 최대 개수를 넘으면 400이고 조회하지 않는다")
    void rejectsInvalidRequest() throws Exception {
        String tooMany = IntStream.range(0, SlotStatusQueryRequest.MAX_SLOT_IDS + 1)
                .mapToObj(i -> "\"" + UUID.randomUUID() + "\"")
                .collect(Collectors.joining(",", "{\"slotIds\":[", "]}"));

        for (String body : List.of("{}", "{\"slotIds\":[null]}", tooMany)) {
            mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        then(slotStatusQueryService).should(never()).getStatuses(any());
    }
}
