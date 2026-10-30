package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;

// GET /api/v1/equipment-incidents 쿼리 파라미터
// institutionId는 ADMIN만 사용한다 (EXAMINER는 항상 자기 기관으로 고정된다)
// 정렬은 최신 장애순으로 고정이며 page·size 규칙은 예약 목록 조회(ReservationSearchCondition)와 같다
public record EquipmentIncidentSearchCondition(
        UUID institutionId,
        UUID equipmentId,
        EquipmentIncidentStatus status,
        Integer page,
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
