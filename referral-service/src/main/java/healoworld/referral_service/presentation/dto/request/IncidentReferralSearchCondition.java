package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

// GET /api/v1/equipment-incidents/{incidentId}/referrals 쿼리 파라미터 (명세: status, page, size)
// 정렬은 작업 생성순으로 고정한다
public record IncidentReferralSearchCondition(
        @Schema(description = "후속 처리 상태")
        IncidentProcessingStatus status,
        @Schema(description = "페이지 번호 (0부터)", example = "0")
        Integer page,
        @Schema(description = "페이지 크기 (10, 30, 50 외에는 10)", example = "10")
        Integer size
) {
    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 30, 50);
    private static final int DEFAULT_SIZE = 10;

    public Pageable toPageable() {
        int pageNumber = (page == null || page < 0) ? 0 : page;
        int pageSize = (size != null && ALLOWED_SIZES.contains(size)) ? size : DEFAULT_SIZE;
        return PageRequest.of(pageNumber, pageSize, Sort.by("createdAt", "id"));
    }
}
