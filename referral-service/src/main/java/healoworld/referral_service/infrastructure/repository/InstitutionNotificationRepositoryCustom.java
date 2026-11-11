package healoworld.referral_service.infrastructure.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface InstitutionNotificationRepositoryCustom {

    // 기관 알림 목록을 조회 사용자의 읽음 기록과 함께 돌려준다
    // referralId·isRead는 선택 필터(null이면 조건 없음). isRead·readAt 정렬은 이 사용자의 읽음 기록 기준이다
    Page<InstitutionNotificationView> searchForUser(UUID institutionId, UUID userId, UUID referralId, Boolean isRead,
                                                    Pageable pageable);
}
