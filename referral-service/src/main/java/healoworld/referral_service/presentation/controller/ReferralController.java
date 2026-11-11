package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.ErrorResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.ExaminationResultResponse;
import healoworld.referral_service.presentation.dto.response.ReferralHistoryResponse;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Referral", description = "검사 의뢰 API")
@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {
    private final ReferralService referralService;
    private final ReferralHistoryService referralHistoryService;
    private final ExaminationResultService examinationResultService;

    @Operation(summary = "의뢰 생성 + 첫 예약 접수",
            description = """
                    의뢰와 첫 예약을 함께 접수합니다. 요청자 소속 기관으로만 생성할 수 있으며 검사기관과 활성 협약이 필요합니다. 동일한 멱등 키로 재요청하면 기존 의뢰를 반환합니다.
                    """)
    // 기존 description의 코드 목록을 상태 코드별로 ErrorResponse와 연결한다. 설명에 없던 코드는 서비스 구현을 확인해 추가했다
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "의뢰 생성·첫 예약 접수 완료 (같은 멱등 키 재요청이면 기존 의뢰)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 필수값 누락 또는 형식 오류
                    - MISSING_HEADER: X-User-Id·X-Institution-Id 헤더 누락
                    - INVALID_INPUT: UUID 형식 또는 요청 본문 형식 오류
                    - EQUIPMENT_NOT_IN_EXAM_INSTITUTION: 장비가 없거나 선택한 검사기관의 장비가 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "REFERRAL_FORBIDDEN, PARTNERSHIP_NOT_ACTIVE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "REFERRAL_IDEMPOTENCY_CONFLICT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "INSTITUTION_SERVICE_UNAVAILABLE: 협력 관계·장비 소속을 확인할 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ReferralResponse>> createReferralWithReservation(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestBody @Valid CreateReferralRequest createReferralRequest
    ) {
        ReferralResponse response = referralService.createReferral(userId, institutionId, createReferralRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("의뢰 생성, 예약 접수가 완료되었습니다.", response));
    }

    @Operation(summary = "재예약 접수",
            description = """
                    재조정이 필요한 의뢰에 새 예약을 접수합니다. 의뢰 작성자 또는 관리자만 가능하며 새 검사기관과 활성 협약이어야 합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "재예약 접수 완료 (새 예약 REQUESTED, 의뢰 PENDING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 필수값 누락 또는 형식 오류
                    - MISSING_HEADER: X-User-Id·X-Role 헤더 누락
                    - INVALID_INPUT: UUID 형식·역할 값 또는 요청 본문 형식 오류
                    - EQUIPMENT_NOT_IN_EXAM_INSTITUTION: 장비가 없거나 선택한 검사기관의 장비가 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "PARTNERSHIP_NOT_ACTIVE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REFERRAL_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_REFERRAL_STATUS, EXAMINATION_RESERVATION_NOT_CHANGEABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "INSTITUTION_SERVICE_UNAVAILABLE: 협력 관계·장비 소속을 확인할 수 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{referralId}/reschedule")
    public ResponseEntity<ApiResponse<ReservationResponse>> createRescheduledReservation(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId,
            @RequestBody @Valid CreateReservationRequest request
    ) {
        ReservationResponse response = referralService.createRescheduledReservation(userId, role, referralId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("재 예약 접수가 완료되었습니다.", response));
    }


    @Operation(summary = "의뢰 단건 조회",
            description = """
                    소속 의뢰기관 또는 관리자가 의뢰 상세를 조회합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REFERRAL_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{referralId}")
    public ResponseEntity<ApiResponse<ReferralResponse>> getReferral(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId
    ) {
        ReferralResponse response = referralService.getReferral(institutionId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "의뢰 목록 조회",
            description = """
                    내 기관의 의뢰를 조회합니다. 관리자는 전체 조회가 가능하며 작성자 ID로 필터링할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: 헤더의 UUID 형식 또는 역할 값이 잘못됨
                    - VALIDATION_FAILED: 검색 조건의 값 형식이 잘못됨
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ReferralResponse>>> getReferrals(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role,
            @ParameterObject @ModelAttribute ReferralSearchCondition cond
    ) {
        PagedResponse<ReferralResponse> response = referralService.getReferrals(institutionId, cond, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "의뢰 변경 내역 조회",
            description = """
                    의뢰 상태 변경 이력을 시간순으로 조회합니다. 소속 의뢰기관 또는 관리자만 조회할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REFERRAL_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{referralId}/histories")
    public ResponseEntity<ApiResponse<List<ReferralHistoryResponse>>> getReferralHistories(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId
    ) {
        List<ReferralHistoryResponse> response = referralHistoryService.getReferralHistories(institutionId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 변경 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "의뢰 삭제",
            description = """
                    FAILED 또는 CANCELED 상태의 의뢰를 소프트 삭제합니다. 작성자 또는 관리자만 가능합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 완료 (data는 null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REFERRAL_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_REFERRAL_STATUS",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{referralId}")
    public ResponseEntity<ApiResponse<Void>> deleteReferral(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId
    ) {
        referralService.deleteReferral(userId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("삭제가 완료되었습니다.", null));
    }

    @Operation(
            summary = "의뢰기관 검사 결과 조회",
            description = """
        REQUESTER는 자기 기관 의뢰의 검사 결과를 조회할 수 있습니다. 결과 요약·추가 소견·등록 일시를 반환합니다.
        """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT, MISSING_HEADER",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "EXAMINATION_RESULT_READ_ACCESS_DENIED",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "REFERRAL_NOT_FOUND, EXAMINATION_NOT_FOUND, EXAMINATION_RESULT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{referralId}/results")
    public ResponseEntity<ApiResponse<ExaminationResultResponse>> getExaminationResult(
            @Parameter(description = "요청자 소속 기관 ID")
            @RequestHeader("X-Institution-Id") UUID institutionId,

            @Parameter(description = "요청자 역할")
            @RequestHeader("X-Role") Role role,

            @Parameter(description = "의뢰 ID")
            @PathVariable("referralId") UUID referralId
    ) {

        ExaminationResultResponse response =
                examinationResultService.getResultByReferralId(
                        referralId,
                        institutionId,
                        role
                );

        return ResponseEntity.ok(
                ApiResponse.ok("검사 결과 조회가 완료되었습니다.", response)
        );
    }
}
