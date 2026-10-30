package healoworld.referral_service.domain.enums;

// Gateway가 JWT의 role 클레임을 X-Role 헤더로 전달하는 값 (Auth의 Role enum과 같은 값을 유지한다)
// 기관 유형(X-Institution-Type: REQUESTER, EXAM, PLATFORM)과는 다른 개념이다
public enum RoleType {
    REQUESTER,  // 의뢰기관 사용자
    EXAMINER,   // 검사기관 사용자
    ADMIN       // 플랫폼 관리자
}
