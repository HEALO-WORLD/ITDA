package healoworld.referral_service.infrastructure.repository;

import java.util.UUID;

// 장비 장애의 영향을 받은 예약 1건 (후속 처리 작업 생성에 필요한 두 값만 조회한다)
// 예약 Entity 전체를 불러오지 않으므로 이 조회로 예약 상태가 실수로 바뀔 일이 없다
public record AffectedReservation(UUID reservationId, UUID referralId) {
}
