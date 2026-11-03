package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.institution.InstitutionService;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.response.PublicInstitutionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/institutions")
public class InstitutionController {
    private final InstitutionService institutionService;

    @GetMapping("/public")
    public ResponseEntity<ApiResponse<List<PublicInstitutionResponse>>> getPublicInstitutions() {
        return ResponseEntity.ok(ApiResponse.ok("가입 가능한 기관 목록입니다.",
                institutionService.getPublicInstitutions()));
    }
}
