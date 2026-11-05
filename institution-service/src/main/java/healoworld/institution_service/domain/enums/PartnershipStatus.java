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

    // 끝난 협약: 다시 요청할 수 있는 상태
    public boolean isClosed(){
        return this == REJECTED || this == TERMINATED;
    }
}
