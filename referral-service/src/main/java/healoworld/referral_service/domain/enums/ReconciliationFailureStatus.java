package healoworld.referral_service.domain.enums;

// 정합성 복구 실패 기록의 처리 상태 (p_reconciliation_failures.status)
// 예약·의뢰 상태값과는 별개다. 예약 도메인 상태는 바꾸지 않고 이 기록으로만 자동 재시도 여부를 판단한다
public enum ReconciliationFailureStatus {
    // 일시적인 오류로 실패, 최대 시도 횟수 전까지 다음 회차에 자동 재시도한다
    RETRYING,
    // 복구 불가능한 업무 오류이거나 최대 시도 횟수를 넘김, 자동 재시도 대상에서 제외하고 담당자가 확인한다
    MANUAL_REVIEW
}
