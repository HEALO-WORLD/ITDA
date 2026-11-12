package healoworld.slot_service.presentation;

import healoworld.slot_service.application.service.EquipmentDowntimeService;
import healoworld.slot_service.domain.enums.Role;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.controller.EquipmentDowntimeController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EquipmentDowntimeController.class)
class EquipmentDowntimeControllerTest {

    private static final String URL = "/api/v1/equipment-downtimes";
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID INSTITUTION_ID = UUID.randomUUID();
    private static final UUID EQUIPMENT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EquipmentDowntimeService equipmentDowntimeService;

    // @EnableJpaAuditing이 메인 클래스에 있어 웹 슬라이스 테스트에서도 JPA 메타모델을 요구한다
    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @Test
    void 등록에_성공하면_201과_응답_본문을_반환한다() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-User-Id", USER_ID)
                        .header("X-Institution-Id", INSTITUTION_ID)
                        .header("X-Role", "EXAMINER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"" + EQUIPMENT_ID + "\"", "\"2026-11-10T09:00:00+09:00\"",
                                "\"2026-11-10T18:00:00+09:00\"", "\"가상 고장 사유\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        verify(equipmentDowntimeService).createDowntime(any(), eq(USER_ID), eq(INSTITUTION_ID), eq(Role.EXAMINER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"equipmentId", "startedAt", "endedAt", "reason"})
    void 필수_값이_없으면_400_VALIDATION_FAILED이고_서비스를_호출하지_않는다(String missing) throws Exception {
        String content = body(
                "equipmentId".equals(missing) ? "null" : "\"" + EQUIPMENT_ID + "\"",
                "startedAt".equals(missing) ? "null" : "\"2026-11-10T09:00:00+09:00\"",
                "endedAt".equals(missing) ? "null" : "\"2026-11-10T18:00:00+09:00\"",
                "reason".equals(missing) ? "null" : "\"가상 고장 사유\"");

        perform(content)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(equipmentDowntimeService, never()).createDowntime(any(), any(), any(), any());
    }

    @Test
    void 사유가_공백뿐이면_400_VALIDATION_FAILED() throws Exception {
        perform(body("\"" + EQUIPMENT_ID + "\"", "\"2026-11-10T09:00:00+09:00\"",
                "\"2026-11-10T18:00:00+09:00\"", "\"   \""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("중단 사유는 필수입니다."));
    }

    @Test
    void 사유가_500자를_넘으면_400_VALIDATION_FAILED() throws Exception {
        perform(body("\"" + EQUIPMENT_ID + "\"", "\"2026-11-10T09:00:00+09:00\"",
                "\"2026-11-10T18:00:00+09:00\"", "\"" + "가".repeat(501) + "\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("중단 사유는 500자 이하여야 합니다."));
    }

    @Test
    void 일시_형식이_잘못되면_400_INVALID_INPUT() throws Exception {
        perform(body("\"" + EQUIPMENT_ID + "\"", "\"2026-11-10 09:00\"",
                "\"2026-11-10T18:00:00+09:00\"", "\"가상 고장 사유\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-User-Id", "X-Institution-Id", "X-Role"})
    void 인증_헤더가_없으면_400_MISSING_HEADER(String omitted) throws Exception {
        var request = post(URL).contentType(MediaType.APPLICATION_JSON)
                .content(body("\"" + EQUIPMENT_ID + "\"", "\"2026-11-10T09:00:00+09:00\"",
                        "\"2026-11-10T18:00:00+09:00\"", "\"가상 고장 사유\""));
        if (!"X-User-Id".equals(omitted)) request.header("X-User-Id", USER_ID);
        if (!"X-Institution-Id".equals(omitted)) request.header("X-Institution-Id", INSTITUTION_ID);
        if (!"X-Role".equals(omitted)) request.header("X-Role", "EXAMINER");

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void 서비스가_권한_없음을_던지면_403_FORBIDDEN() throws Exception {
        given(equipmentDowntimeService.createDowntime(any(), any(), any(), any()))
                .willThrow(new BaseException(ErrorCode.FORBIDDEN));

        perform(body("\"" + EQUIPMENT_ID + "\"", "\"2026-11-10T09:00:00+09:00\"",
                "\"2026-11-10T18:00:00+09:00\"", "\"가상 고장 사유\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    private ResultActions perform(String content) throws Exception {
        return mockMvc.perform(post(URL)
                .header("X-User-Id", USER_ID)
                .header("X-Institution-Id", INSTITUTION_ID)
                .header("X-Role", "EXAMINER")
                .contentType(MediaType.APPLICATION_JSON)
                .content(content));
    }

    private String body(String equipmentId, String startedAt, String endedAt, String reason) {
        return """
                {"equipmentId": %s, "startedAt": %s, "endedAt": %s, "reason": %s, "downtimeType": "FAILURE"}
                """.formatted(equipmentId, startedAt, endedAt, reason);
    }
}
