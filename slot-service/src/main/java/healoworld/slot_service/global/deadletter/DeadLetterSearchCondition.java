package healoworld.slot_service.global.deadletter;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

// GET /api/v1/referral-dead-letters 쿼리 파라미터
// 정렬은 최신 저장순으로 고정이며 page·size 규칙은 장비 장애 목록 조회(EquipmentIncidentSearchCondition)와 같다
public record DeadLetterSearchCondition(
        @Schema(description = "처리 상태. 생략하면 전체")
        DeadLetterStatus status,
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
        return PageRequest.of(pageNumber, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"));
    }
}
