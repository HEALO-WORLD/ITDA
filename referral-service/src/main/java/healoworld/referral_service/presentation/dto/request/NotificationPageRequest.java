package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// GET /api/v1/notifications의 page·size·sort 정책
// sort는 요청 원문을 받아 검증한다. Spring의 Pageable 해석기는 sort=desc 같은 잘못된 값을 오류 없이 무시하기 때문이다
public final class NotificationPageRequest {

    private static final Set<Integer> ALLOWED_SIZES = Set.of(10, 30, 50);
    private static final int DEFAULT_SIZE = 10;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "readAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private NotificationPageRequest() {
    }

    // page는 숫자가 아니거나 음수이면 0, size는 10·30·50이 아니면 10을 쓴다
    // sort는 값이 없으면 기본 정렬을 쓰고, 값이 하나라도 잘못되면 400 INVALID_INPUT이다
    public static Pageable of(String page, String size, String[] sorts) {
        int pageNumber = Math.max(parseOrDefault(page, 0), 0);
        int parsedSize = parseOrDefault(size, DEFAULT_SIZE);
        int pageSize = ALLOWED_SIZES.contains(parsedSize) ? parsedSize : DEFAULT_SIZE;
        return PageRequest.of(pageNumber, pageSize, toSort(sorts));
    }

    private static Sort toSort(String[] sorts) {
        if (sorts == null || sorts.length == 0) {
            return DEFAULT_SORT;
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String sort : sorts) {
            orders.add(toOrder(sort));
        }
        return Sort.by(orders);
    }

    // "필드" 또는 "필드,방향" 형식만 허용한다. 방향을 생략하면 기존 Pageable과 같이 오름차순이다
    private static Sort.Order toOrder(String sort) {
        String[] parts = sort.split(",", -1);
        if (parts.length > 2 || !ALLOWED_SORT_FIELDS.contains(parts[0])) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
        if (parts.length == 1) {
            return Sort.Order.asc(parts[0]);
        }
        // Direction.fromOptionalString은 대소문자를 구분하지 않고 ASC, DESC만 받는다
        Optional<Sort.Direction> direction = Sort.Direction.fromOptionalString(parts[1]);
        if (direction.isEmpty()) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
        return new Sort.Order(direction.get(), parts[0]);
    }

    private static int parseOrDefault(String value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
