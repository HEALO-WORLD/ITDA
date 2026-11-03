package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.examtype.ExamTypeService;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.ExamTypeCreateRequest;
import healoworld.institution_service.presentation.dto.request.ExamTypeUpdateRequest;
import healoworld.institution_service.presentation.dto.response.ExamTypeCreateResponse;
import healoworld.institution_service.presentation.dto.response.ExamTypeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/exam-types")
public class ExamTypeController {

    private final ExamTypeService examTypeService;

    @PostMapping
    public ResponseEntity<ApiResponse<ExamTypeCreateResponse>> createExamType(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody ExamTypeCreateRequest request
    ) {

        ExamTypeCreateResponse response = examTypeService.createExamType(request, userId, userRole);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("검사 유형이 등록되었습니다.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ExamTypeResponse>>> getExamTypes(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Role") Role userRole,
            @RequestParam(name = "modality", required = false) Modality modality,
            @RequestParam(name = "page", defaultValue = "0") Integer page,
            @RequestParam(name = "size", defaultValue = "20") Integer size
    ) {
        PagedResponse<ExamTypeResponse> response = examTypeService.getExamTypes(userId, userRole, modality, page, size);

        return ResponseEntity.ok(ApiResponse.ok("검사 유형 목록 조회에 성공했습니다.", response));
    }

    @PatchMapping("/{examTypeId}")
    public ResponseEntity<ApiResponse<ExamTypeResponse>> updateExamType(
            @PathVariable("examTypeId") UUID examTypeId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody ExamTypeUpdateRequest request
    ) {
        ExamTypeResponse response = examTypeService.updateExamType(
                examTypeId, request, userId, userRole
        );

        return ResponseEntity.ok(
                ApiResponse.ok("검사 유형이 수정되었습니다.", response)
        );
    }
}