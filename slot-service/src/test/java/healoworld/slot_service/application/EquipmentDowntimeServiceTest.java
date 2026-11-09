package healoworld.slot_service.application;

import healoworld.slot_service.application.service.EquipmentDowntimeService;
import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import feign.FeignException;
import feign.Request;
import feign.Response;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.client.EquipmentVisibilityClient;
import healoworld.slot_service.infrastructure.client.InstitutionClient;
import healoworld.slot_service.infrastructure.client.dto.EquipmentOwnerResponse;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.presentation.dto.request.EquipmentDowntimeCreateRequest;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeCreateResponse;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeServiceTest extends PostgresContainerSupport {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID INSTITUTION_ID = UUID.randomUUID();
    private static final UUID EQUIPMENT_ID = UUID.randomUUID();

    @Autowired
    private EquipmentDowntimeService equipmentDowntimeService;

    @Autowired
    private EquipmentDowntimeRepository equipmentDowntimeRepository;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    // Institution Service 호출은 대체한다
    @MockitoBean
    private EquipmentVisibilityClient equipmentVisibilityClient;

    // 소유 기관 조회는 Feign 인터페이스를 대체해 EquipmentOwnerClient의 응답 검증까지 실제로 거친다
    @MockitoBean
    private InstitutionClient institutionClient;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    @Test
    void EXAMINER가_자기_기관_장비를_등록하면_한국_시간으로_저장하고_이벤트도_한국_시간이다() {
        examinerOwns(EQUIPMENT_ID);
        ownerIs(EQUIPMENT_ID, INSTITUTION_ID);

        // UTC 00:00 = KST 09:00
        EquipmentDowntimeCreateResponse response = equipmentDowntimeService.createDowntime(
                request(OffsetDateTime.parse("2026-11-10T00:00:00Z"),
                        OffsetDateTime.parse("2026-11-10T18:00:00+09:00"), "  가상 고장 사유  "),
                USER_ID, INSTITUTION_ID, "EXAMINER");

        EquipmentDowntime saved = equipmentDowntimeRepository.findById(response.getId()).orElseThrow();
        assertThat(saved.getEquipmentId()).isEqualTo(EQUIPMENT_ID);
        assertThat(saved.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 0));
        assertThat(saved.getEndedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 18, 0));
        assertThat(saved.getReason()).isEqualTo("가상 고장 사유");
        assertThat(saved.getDeletedAt()).isNull();

        assertThat(response.getStartedAt()).isEqualTo(OffsetDateTime.parse("2026-11-10T09:00:00+09:00"));
        assertThat(response.getEndedAt()).isEqualTo(OffsetDateTime.parse("2026-11-10T18:00:00+09:00"));

        JsonNode payload = singlePayload();
        assertThat(payload.get("institutionId").asString()).isEqualTo(INSTITUTION_ID.toString());
        assertThat(payload.get("startAt").asString()).isEqualTo("2026-11-10T09:00:00");
        assertThat(payload.get("endAt").asString()).isEqualTo("2026-11-10T18:00:00");
        assertThat(payload.get("reason").asString()).isEqualTo("가상 고장 사유");
    }

    @Test
    void EXAMINER의_기관과_장비의_실제_소속_기관이_다르면_403이고_아무것도_저장하지_않는다() {
        examinerOwns(EQUIPMENT_ID);
        ownerIs(EQUIPMENT_ID, UUID.randomUUID());

        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                validRequest(), USER_ID, INSTITUTION_ID, "EXAMINER"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertNothingSaved();
    }

    @Test
    void ADMIN이면_요청자_기관이_아닌_장비의_실제_소속_기관을_이벤트에_담는다() {
        UUID ownerInstitutionId = UUID.randomUUID();
        ownerIs(EQUIPMENT_ID, ownerInstitutionId);

        EquipmentDowntimeCreateResponse response = equipmentDowntimeService.createDowntime(
                validRequest(), USER_ID, INSTITUTION_ID, "ADMIN");

        assertThat(equipmentDowntimeRepository.existsById(response.getId())).isTrue();
        assertThat(singlePayload().get("institutionId").asString()).isEqualTo(ownerInstitutionId.toString());
        // ADMIN은 전체 장비 목록을 받아 비교하지 않는다
        verify(equipmentVisibilityClient, never()).getVisibleEquipmentIds(any(), any(), any(), anyBoolean());
    }

    @Test
    void EXAMINER가_자기_기관_장비가_아닌_장비를_지정하면_403이고_저장하지_않는다() {
        examinerOwns(UUID.randomUUID());

        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                validRequest(), USER_ID, INSTITUTION_ID, "EXAMINER"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        assertNothingSaved();
    }

    @Test
    void ADMIN이_없는_장비를_지정하면_404이고_저장하지_않는다() {
        given(institutionClient.getEquipmentOwner(EQUIPMENT_ID)).willThrow(feignError(404));

        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                validRequest(), USER_ID, INSTITUTION_ID, "ADMIN"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertNothingSaved();
    }

    @Test
    void 소속_기관_조회가_실패하면_503이고_장애와_이벤트를_저장하지_않는다() {
        given(institutionClient.getEquipmentOwner(EQUIPMENT_ID)).willThrow(feignError(503));

        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                validRequest(), USER_ID, INSTITUTION_ID, "ADMIN"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFERENCE_SERVICE_UNAVAILABLE);
        assertNothingSaved();
    }

    @Test
    void 소속_기관_응답이_비었거나_다른_장비의_응답이면_502이고_저장하지_않는다() {
        given(institutionClient.getEquipmentOwner(EQUIPMENT_ID)).willReturn(
                ApiResponse.ok("ok", new EquipmentOwnerResponse(UUID.randomUUID(), UUID.randomUUID())),
                ApiResponse.ok("ok", new EquipmentOwnerResponse(EQUIPMENT_ID, null)));

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                    validRequest(), USER_ID, INSTITUTION_ID, "ADMIN"))
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.REFERENCE_RESPONSE_INVALID);
        }
        assertNothingSaved();
    }

    @ParameterizedTest
    @ValueSource(strings = {"REQUESTER", "UNKNOWN"})
    void EXAMINER_ADMIN이_아니면_403이고_장비_조회도_하지_않는다(String role) {
        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                validRequest(), USER_ID, INSTITUTION_ID, role))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(equipmentVisibilityClient, never()).getVisibleEquipmentIds(any(), any(), any(), anyBoolean());
    }

    @Test
    void 종료가_시작과_같거나_빠르면_400이고_장비_조회도_하지_않는다() {
        OffsetDateTime at = OffsetDateTime.parse("2026-11-10T09:00:00+09:00");

        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                request(at, at, "가상 고장 사유"), USER_ID, INSTITUTION_ID, "EXAMINER"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOWNTIME_INVALID_TIME_RANGE);

        // 오프셋이 달라도 같은 순간이면 길이 0인 구간이다 (KST 09:00 = UTC 00:00)
        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                request(at, OffsetDateTime.parse("2026-11-10T00:00:00Z"), "가상 고장 사유"),
                USER_ID, INSTITUTION_ID, "EXAMINER"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOWNTIME_INVALID_TIME_RANGE);

        verify(equipmentVisibilityClient, never()).getVisibleEquipmentIds(any(), any(), any(), anyBoolean());
    }

    @Test
    void 이미_시작된_장애도_종료가_미래면_등록한다() {
        examinerOwns(EQUIPMENT_ID);
        ownerIs(EQUIPMENT_ID, INSTITUTION_ID);

        // 고정 시각 2026-10-30 10:00 기준으로 시작은 과거, 종료는 미래
        EquipmentDowntimeCreateResponse response = equipmentDowntimeService.createDowntime(
                request(OffsetDateTime.parse("2026-10-30T08:00:00+09:00"),
                        OffsetDateTime.parse("2026-10-30T18:00:00+09:00"), "가상 고장 사유"),
                USER_ID, INSTITUTION_ID, "EXAMINER");

        assertThat(equipmentDowntimeRepository.existsById(response.getId())).isTrue();
    }

    @Test
    void 종료가_현재_이전이거나_같으면_400이고_장비_조회도_하지_않는다() {
        assertThatThrownBy(() -> equipmentDowntimeService.createDowntime(
                request(OffsetDateTime.parse("2026-10-30T08:00:00+09:00"),
                        OffsetDateTime.parse("2026-10-30T10:00:00+09:00"), "가상 고장 사유"),
                USER_ID, INSTITUTION_ID, "EXAMINER"))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOWNTIME_END_NOT_FUTURE);

        verify(equipmentVisibilityClient, never()).getVisibleEquipmentIds(any(), any(), any(), anyBoolean());
        assertThat(equipmentDowntimeRepository.count()).isZero();
    }

    private void examinerOwns(UUID equipmentId) {
        given(equipmentVisibilityClient.getVisibleEquipmentIds(INSTITUTION_ID, "EXAMINER", null, false))
                .willReturn(Set.of(equipmentId));
    }

    private void ownerIs(UUID equipmentId, UUID institutionId) {
        given(institutionClient.getEquipmentOwner(equipmentId))
                .willReturn(ApiResponse.ok("ok", new EquipmentOwnerResponse(equipmentId, institutionId)));
    }

    private FeignException feignError(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "/api/v1/internal/equipments/x/owner",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("getEquipmentOwner",
                Response.builder().status(status).request(request).headers(Map.of()).build());
    }

    private JsonNode singlePayload() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        return jsonMapper.readTree(events.getFirst().getPayload());
    }

    private void assertNothingSaved() {
        assertThat(equipmentDowntimeRepository.count()).isZero();
        assertThat(outboxEventRepository.count()).isZero();
    }

    private EquipmentDowntimeCreateRequest validRequest() {
        return request(OffsetDateTime.parse("2026-11-10T09:00:00+09:00"),
                OffsetDateTime.parse("2026-11-10T18:00:00+09:00"), "가상 고장 사유");
    }

    private EquipmentDowntimeCreateRequest request(OffsetDateTime startedAt, OffsetDateTime endedAt, String reason) {
        EquipmentDowntimeCreateRequest request = new EquipmentDowntimeCreateRequest();
        ReflectionTestUtils.setField(request, "equipmentId", EQUIPMENT_ID);
        ReflectionTestUtils.setField(request, "startedAt", startedAt);
        ReflectionTestUtils.setField(request, "endedAt", endedAt);
        ReflectionTestUtils.setField(request, "reason", reason);
        return request;
    }
}
