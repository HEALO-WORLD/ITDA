package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.enums.ReferralStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;
import java.util.UUID;

public record ReferralSearchCondition(
        @Schema(description = "의뢰 상태")
        ReferralStatus status,
        @Schema(description = "의뢰 기관 ID (관리자 필터용)")
        UUID referringInstitutionId,
        @Schema(description = "작성자 ID. 본인 ID를 넣으면 내가 작성한 의뢰만 조회")
        UUID referrerId,
        @Schema(description = "검사 종류 ID")
        UUID examTypeId,
        @Schema(description = "환자 이름 (부분 일치)", example = "홍")
        String patientName,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        Integer page,
        @Schema(description = "페이지 크기 (10, 30, 50 외에는 10)", example = "10")
        Integer size,
        @Schema(description = "정렬 (createdAt | updatedAt),(asc | desc)", example = "createdAt,desc")
        String sort
) {
    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 30, 50);
    private static final int DEFAULT_SIZE = 10;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "updatedAt");
    private static final String DEFAULT_SORT_FIELD = "createdAt";

    public Pageable toPageable() {
        int pageNumber = (page == null || page < 0) ? 0 : page;
        int pageSize = (size != null && ALLOWED_SIZES.contains(size)) ? size : DEFAULT_SIZE;
        return PageRequest.of(pageNumber, pageSize, toSort());
    }

    private Sort toSort() {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.DESC, DEFAULT_SORT_FIELD);
        }

        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            return Sort.by(Sort.Direction.DESC, DEFAULT_SORT_FIELD);
        }

        Sort.Direction direction = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;
        return Sort.by(direction, field);
    }
}
