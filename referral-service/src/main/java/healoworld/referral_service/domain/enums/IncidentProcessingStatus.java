package healoworld.referral_service.domain.enums;

// p_incident_referrals.processing_status: 장애로 영향받은 의뢰 1건의 후속 처리 상태
// 의뢰 상태·검사 상태와는 다른 값이다
// 흐름: PENDING → PROCESSING → SUCCEEDED / FAILED, 재처리 시 FAILED → PROCESSING
// 실제 전이는 동시 실행을 막기 위해 IncidentReferralRepository의 조건부 UPDATE로만 수행한다
public enum IncidentProcessingStatus {
    PENDING,     // 후속 처리 대기
    PROCESSING,  // 후속 처리 진행 중 (locked_until까지 작업자가 선점)
    SUCCEEDED,   // 재조정 필요 상태 변경 등 후속 처리 성공
    FAILED       // 후속 처리 실패 (재처리 대상)
}
