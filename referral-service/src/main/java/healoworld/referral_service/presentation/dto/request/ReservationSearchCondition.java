package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

public record ReservationSearchCondition(
        UUID referralId,
        UUID referrerId,
        UUID examInstitutionId,
        UUID equipmentId,
        ReservationStatus status,
        FailureReason failureReason,
        LocalDateTime examDateFrom,
        LocalDateTime examDateTo,
        Integer page,
        Integer size,
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
