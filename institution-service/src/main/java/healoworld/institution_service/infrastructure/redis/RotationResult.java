package healoworld.institution_service.infrastructure.redis;

// Lua 스크립트의 결과를 담는 그릇
public record RotationResult(Status status, String graceValue) {

    public enum Status {
        ROTATED,    // 정상 교체
        GRACE,      // 유예 시간 안의 동시 요청 -> 방금 발급한 토큰 쌍을 그대로 돌려줌
        REUSED,     // 이미 교체된 토큰 재사용 -> 탈취로 판단
        MISSING     // 키 없음 (만료, 로그아웃, 폐기)
    }
}
