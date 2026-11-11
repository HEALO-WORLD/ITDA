package healoworld.institution_service.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class RefreshTokenRepository {
    private static final String FAMILY_KEY_PREFIX = "auth:refresh:family:";
    private static final String GRACE_KEY_PREFIX = "auth:refresh:grace:";

    /*
     * 비교 → 교체 → 유예 키 저장을 Redis 안에서 한 번에 실행한다 (중간에 다른 요청이 끼어들 수 없음)
     * KEYS[1] family 키, KEYS[2] 유예 키(제출된 jti 기준)
     * ARGV[1] 제출된 jti, ARGV[2] 새 jti, ARGV[3] family TTL(초), ARGV[4] 유예용 토큰 쌍, ARGV[5] 유예 TTL(초)
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ROTATE_SCRIPT = RedisScript.of("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                return {'MISSING'}
            end
            if current == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
                redis.call('SET', KEYS[2], ARGV[4], 'EX', ARGV[5])
                return {'ROTATED'}
            end
            local grace = redis.call('GET', KEYS[2])
            if grace then
                return {'GRACE', grace}
            end
            redis.call('DEL', KEYS[1])
            return {'REUSED'}
            """, List.class);

    private final StringRedisTemplate redisTemplate;

    // 로그인: 새 family 시작
    public void save(UUID familyId, UUID tokenId, Duration ttl) {
        redisTemplate.opsForValue().set(familyKey(familyId), tokenId.toString(), ttl);
    }

    public RotationResult rotate(UUID familyId, UUID presentedTokenId, UUID newTokenId,
                                 Duration familyTtl, String graceValue, Duration graceTtl) {
        List<?> result = redisTemplate.execute(ROTATE_SCRIPT,
                List.of(familyKey(familyId), graceKey(familyId, presentedTokenId)),
                presentedTokenId.toString(),               // ARGV[1] 제출된 jti
                newTokenId.toString(),                     // ARGV[2] 새 jti
                String.valueOf(familyTtl.toSeconds()),     // ARGV[3] family TTL
                graceValue,                                // ARGV[4] 유예용 토큰 쌍
                String.valueOf(graceTtl.toSeconds()));     // ARGV[5] 유예 TTL

        RotationResult.Status status = RotationResult.Status.valueOf((String) result.get(0));
        String grace = result.size() > 1 ? (String) result.get(1) : null;
        return new RotationResult(status, grace);
    }

    // 로그아웃, 폐기
    public void delete(UUID familyId) {
        redisTemplate.delete(familyKey(familyId));
    }

    private String graceKey(UUID familyId, UUID tokenId) {
        return GRACE_KEY_PREFIX + familyId + ":" + tokenId;
    }

    private String familyKey(UUID familyId) {
        return FAMILY_KEY_PREFIX + familyId;
    }
}
