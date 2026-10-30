package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record ReservationSearchCondition(
        @Schema(description = "의뢰 ID. 한 의뢰의 예약 이력 조회")
        UUID referralId,
        @Schema(description = "의뢰 작성자 ID")
        UUID referrerId,
        @Schema(description = "검사 기관 ID")
        UUID examInstitutionId,
        @Schema(description = "장비 ID")
        UUID equipmentId,
        @Schema(description = "예약 상태")
        ReservationStatus status,
        @Schema(description = "실패 사유")
        FailureReason failureReason,
        @Schema(description = "검사일 시작 (포함)", example = "2026-11-01T00:00:00")
        LocalDateTime examDateFrom,
        @Schema(description = "검사일 끝 (포함)", example = "2026-11-30T23:59:59")
        LocalDateTime examDateTo,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        Integer page,
        @Schema(description = "페이지 크기 (10, 30, 50 외에는 10)", example = "10")
        Integer size,
        @Schema(description = "정렬 (createdAt | updatedAt | examDate | attemptNo),(asc | desc)", example = "createdAt,desc")
        String sort
) {
    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 30, 50);
    private static final int DEFAULT_SIZE = 10;

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "updatedAt", "examDate", "attemptNo");
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
