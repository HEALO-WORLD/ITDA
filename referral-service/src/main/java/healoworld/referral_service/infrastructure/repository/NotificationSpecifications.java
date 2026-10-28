package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Notification;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 알림 목록 조회의 선택 필터(referralId, isRead)를 값이 있을 때만 WHERE 조건에 추가한다
public final class NotificationSpecifications {

    private NotificationSpecifications() {
    }

    public static Specification<Notification> searchMine(UUID recipientUserId, UUID referralId, Boolean isRead) {
        List<Specification<Notification>> conditions = new ArrayList<>();

        // 본인 알림만 조회 (항상 적용)
        conditions.add((root, query, cb) -> cb.equal(root.get("recipientUserId"), recipientUserId));

        if (referralId != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("referralId"), referralId));
        }
        if (isRead != null) {
            conditions.add((root, query, cb) -> cb.equal(root.get("isRead"), isRead));
        }
        return Specification.allOf(conditions);
    }
}
