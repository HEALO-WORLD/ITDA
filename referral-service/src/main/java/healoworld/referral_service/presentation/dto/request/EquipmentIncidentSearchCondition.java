package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;

// GET /api/v1/equipment-incidents 쿼리 파라미터
// institutionId는 ADMIN만 사용한다 (EXAMINER는 항상 자기 기관으로 고정된다)
// 정렬은 최신 장애순으로 고정이며 page·size 규칙은 예약 목록 조회(ReservationSearchCondition)와 같다
public record EquipmentIncidentSearchCondition(
        @Schema(description = "기관 ID. ADMIN에게만 적용되고 EXAMINER는 자기 기관으로 고정됨")
        UUID institutionId,
        @Schema(description = "장비 ID")
        UUID equipmentId,
        @Schema(description = "장애 상태")
        EquipmentIncidentStatus status,
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
        return PageRequest.of(pageNumber, pageSize);
    }
}
