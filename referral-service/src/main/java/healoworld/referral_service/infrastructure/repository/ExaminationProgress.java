package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.enums.ExaminationStatus;

import java.util.UUID;

// 검사 1건의 진행 정보 (장애 영향 의뢰의 수동 확인 여부 판단에 필요한 세 값만 조회한다)
// 검사 Entity 전체를 불러오지 않으므로 이 조회로 검사 상태가 실수로 바뀔 일이 없다
public record ExaminationProgress(UUID referralId, UUID reservationId, ExaminationStatus status) {
}
