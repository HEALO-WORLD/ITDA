package healoworld.institution_service.domain.enums;

import java.util.EnumSet;
import java.util.Set;

public enum PartnershipStatus {
    PENDING,
    ACTIVE,
    REJECTED,
    TERMINATED;

    // 진행 중(삭제 등을 막는) 상태
    public static Set<PartnershipStatus> inProgress() {
        return EnumSet.of(PENDING, ACTIVE);
    }
}
