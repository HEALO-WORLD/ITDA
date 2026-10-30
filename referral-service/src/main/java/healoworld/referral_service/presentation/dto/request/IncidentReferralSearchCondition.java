package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

// GET /api/v1/equipment-incidents/{incidentId}/referrals 쿼리 파라미터 (명세: status, page, size)
// 정렬은 작업 생성순으로 고정한다
public record IncidentReferralSearchCondition(
        IncidentProcessingStatus status,
        Integer page,
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
