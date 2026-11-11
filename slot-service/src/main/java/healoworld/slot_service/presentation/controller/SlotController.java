package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.service.SlotQueryService;
import healoworld.slot_service.application.service.SlotService;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.dto.ErrorResponse;
import healoworld.slot_service.global.dto.SliceResponse;
import healoworld.slot_service.presentation.dto.request.SlotCreateRequest;
import healoworld.slot_service.presentation.dto.request.SlotGenerateRequest;
import healoworld.slot_service.presentation.dto.request.SlotSearchRequest;
import healoworld.slot_service.presentation.dto.response.SlotCreateResponse;
import healoworld.slot_service.presentation.dto.response.SlotDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

// Swagger 응답 어노테이션은 공통 응답 DTO(ApiResponse)와 이름이 같아 완전한 이름으로 쓴다
// 성공 응답은 content를 비워 springdoc이 반환형(ApiResponse<T>)으로 스키마를 만들게 하고, 오류 응답만 ErrorResponse를 지정한다
@Tag(name = "Slot", description = """
        슬롯 등록·일괄 생성·조회 API (인증 필요).
        """)
@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;
    private final SlotQueryService slotQueryService;

    @Operation(summary = "슬롯 등록",
            description = """
                    운영시간 내 미래 시각의 슬롯 1개를 등록합니다. 검사 소요시간 이상이어야 하며 기존 슬롯·중단 구간과 겹칠 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료 (상태 AVAILABLE)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 필수 값 누락, 시작 일시가 현재 이후가 아님
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: UUID·일시 형식 오류, 요청 본문 형식 오류, Institution Service가 400으로 응답(예: 장비 종류와 검사 방식이 다름)
                    - SLOT_START_NOT_FUTURE: 시작 일시가 현재 이후가 아님
                    - SLOT_INVALID_TIME_RANGE: 종료 일시가 시작 일시 이후가 아님
                    - SLOT_DATE_MISMATCH: 시작·종료가 한국 시간 기준 다른 날짜
                    - SLOT_OUTSIDE_OPERATING_HOURS: 운영시간 밖
                    - SLOT_DURATION_MISMATCH: 슬롯 길이가 검사 소요시간보다 짧음
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER·ADMIN이 아니거나, EXAMINER가 다른 기관 장비에 등록",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비·기관·검사 유형이 없거나 장비에 등록되지 않은 검사 유형 (Institution Service 404)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = """
                    - SLOT_TIME_OVERLAP: 같은 장비에 시간이 겹치는 슬롯이 있음
                    - SLOT_IN_DOWNTIME: 장비 중단 구간과 겹침
                    - REFERENCE_NOT_AVAILABLE: 장비가 OPERATIONAL이 아니거나 해당 요일 운영시간이 없음 (Institution Service 409)
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "REFERENCE_RESPONSE_INVALID: Institution Service 응답이 올바르지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "REFERENCE_SERVICE_UNAVAILABLE: Institution Service 응답 없음 또는 5xx",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<SlotCreateResponse>> createSlot(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한 (EXAMINER, ADMIN)") @RequestHeader("X-Role") String userRole,
            @Valid @RequestBody SlotCreateRequest request
    ) {
        SlotCreateResponse response = slotService.createSlot(
                request,
                userId,
                userInstitutionId,
                userRole
        );

        URI location = URI.create("/api/v1/slots/" + response.getId());

        return ResponseEntity.created(location).body(ApiResponse.ok("슬롯이 등록되었습니다.", response));
    }

    @Operation(summary = "슬롯 단건 조회",
            description = """
                    접근 가능한 장비의 슬롯을 조회합니다. 의뢰기관은 활성 협약 검사기관의 정상 장비 슬롯만 조회할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: slotId·헤더의 UUID 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 허용되지 않은 역할, 볼 수 없는 장비의 슬롯, 요청자 기관이 비활성이거나 역할과 맞지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 슬롯이 없거나 삭제됨, 또는 요청자 기관이 없음 (Institution Service 404)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "REFERENCE_RESPONSE_INVALID: Institution Service 응답이 올바르지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "REFERENCE_SERVICE_UNAVAILABLE: Institution Service 응답 없음 또는 5xx",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{slotId}")
    public ResponseEntity<ApiResponse<SlotDetailResponse>> getSlot(
            @Parameter(description = "슬롯 ID") @PathVariable("slotId") UUID slotId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한 (REQUESTER, EXAMINER, ADMIN)") @RequestHeader("X-Role") String userRole
    ) {
        SlotDetailResponse response = slotQueryService.getSlot(
                slotId,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("슬롯 조회에 성공했습니다.", response));
    }

    @Operation(summary = "슬롯 일괄 생성 (하루)",
            description = """
                    지정 날짜의 운영시간을 검사 소요시간 단위로 나눠 슬롯을 생성합니다. 기존 슬롯·장비 중단 구간과 하나라도 겹치면 전체 생성을 취소합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "생성 완료 (생성된 슬롯 목록, 시작 시각순)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: equipmentId·examTypeId·date 누락
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: UUID·날짜 형식 오류, 요청 본문 형식 오류, Institution Service가 400으로 응답
                    - SLOT_START_NOT_FUTURE: date가 오늘 이전
                    - SLOT_GENERATION_EMPTY: 만들 수 있는 미래 슬롯이 없음
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER·ADMIN이 아니거나, EXAMINER가 다른 기관 장비를 지정",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비·기관·검사 유형이 없거나 장비에 등록되지 않은 검사 유형 (Institution Service 404)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = """
                    - SLOT_TIME_OVERLAP: 후보 중 기존 슬롯과 겹치는 시간이 있음 (아무것도 만들지 않음)
                    - SLOT_IN_DOWNTIME: 후보 중 장비 중단 구간과 겹치는 시간이 있음 (아무것도 만들지 않음)
                    - REFERENCE_NOT_AVAILABLE: 장비가 OPERATIONAL이 아니거나 해당 요일 운영시간이 없음 (Institution Service 409)
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "REFERENCE_RESPONSE_INVALID: Institution Service 응답이 올바르지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "REFERENCE_SERVICE_UNAVAILABLE: Institution Service 응답 없음 또는 5xx",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<SlotCreateResponse>>> generateSlots(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한 (EXAMINER, ADMIN)") @RequestHeader("X-Role") String userRole,
            @Valid @RequestBody SlotGenerateRequest request
    ) {
        List<SlotCreateResponse> responses = slotService.generateSlots(
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("슬롯이 일괄 등록되었습니다.", responses));
    }

    @Operation(summary = "슬롯 검색·목록 조회",
            description = """
                    기간·장비·검사 유형·기관 조건으로 슬롯을 조회합니다. SEARCH는 예약 가능한 슬롯, MANAGEMENT는 관리용 목록이며 의뢰기관은 SEARCH만 사용할 수 있습니다. 조회 기간은 최대 31일입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: from·to 누락, page·size 범위 오류, 일시·UUID·enum 값 형식 오류
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: 헤더의 UUID 형식 오류
                    - SLOT_INVALID_SEARCH_RANGE: to가 from 이후가 아님
                    - SLOT_SEARCH_RANGE_EXCEEDED: 검색 기간이 31일 초과
                    - SLOT_INVALID_SEARCH_STATUS: SEARCH 모드에서 AVAILABLE 외 상태 요청
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 허용되지 않은 역할, REQUESTER의 MANAGEMENT 요청, 요청자 기관이 비활성이거나 역할과 맞지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 요청자 기관이 없음 (Institution Service 404)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "REFERENCE_RESPONSE_INVALID: Institution Service 응답이 올바르지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "REFERENCE_SERVICE_UNAVAILABLE: Institution Service 응답 없음 또는 5xx",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<SliceResponse<SlotDetailResponse>>> searchSlots(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한 (REQUESTER, EXAMINER, ADMIN)") @RequestHeader("X-Role") String userRole,
            @ParameterObject @Valid @ModelAttribute SlotSearchRequest request
    ) {
        SliceResponse<SlotDetailResponse> response =
                slotQueryService.searchSlots(
                        request,
                        userId,
                        userInstitutionId,
                        userRole
                );

        return ResponseEntity.ok(ApiResponse.ok("슬롯 검색에 성공했습니다.", response));
    }
}