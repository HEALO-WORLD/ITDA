package healoworld.referral_service.domain.enums;

// 검사 진행 상태: SCHEDULED → IN_PROGRESS → COMPLETED
public enum ExaminationStatus {
    SCHEDULED,
    IN_PROGRESS,
    COMPLETED;

    // 바로 다음 단계로만 이동할 수 있다 (건너뛰기·되돌리기 금지). 같은 상태 요청은 호출하는 쪽에서 먼저 걸러낸다
    public boolean canTransitionTo(ExaminationStatus target) {
        return switch (this) {
            case SCHEDULED -> target == IN_PROGRESS;
            case IN_PROGRESS -> target == COMPLETED;
            case COMPLETED -> false;
        };
    }
}
