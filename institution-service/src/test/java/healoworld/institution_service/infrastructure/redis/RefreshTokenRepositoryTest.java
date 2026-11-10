package healoworld.institution_service.infrastructure.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

// Lua 스크립트는 Mock으로 검증할 수 없어서 실제 Redis 컨테이너로 테스트한다
// Spring 컨텍스트 없이 Redis만 띄워서 빠르게 돈다
public class RefreshTokenRepositoryTest {

    private static final Duration FAMILY_TTL = Duration.ofDays(14);
    private static final Duration GRACE_TTL = Duration.ofSeconds(1);   // 테스트용으로 짧게

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.10.3")).withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private static RefreshTokenRepository repository;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        repository = new RefreshTokenRepository(redisTemplate);
    }

    @AfterAll
    static void stopRedis() {
        connectionFactory.destroy();
        REDIS.stop();
    }

    @Test
    @DisplayName("로그인하면 family 키에 jti가 14일 TTL로 저장된다")
    void save() {
        UUID familyId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        repository.save(familyId, tokenId, FAMILY_TTL);

        assertThat(currentJti(familyId)).isEqualTo(tokenId.toString());
        assertThat(redisTemplate.getExpire(familyKey(familyId))).isGreaterThan(FAMILY_TTL.toSeconds() - 5);
    }

    @Test
    @DisplayName("현재 jti로 재발급하면 ROTATED, 새 jti로 교체되고 유예 키가 생긴다")
    void rotate_current() {
        UUID familyId = UUID.randomUUID();
        UUID oldJti = UUID.randomUUID();
        UUID newJti = UUID.randomUUID();
        repository.save(familyId, oldJti, FAMILY_TTL);

        RotationResult result = rotate(familyId, oldJti, newJti, "access refresh");

        assertThat(result.status()).isEqualTo(RotationResult.Status.ROTATED);
        assertThat(currentJti(familyId)).isEqualTo(newJti.toString());
        assertThat(redisTemplate.opsForValue().get(graceKey(familyId, oldJti))).isEqualTo("access refresh");
    }

    @Test
    @DisplayName("유예 시간 안에 옛 jti로 다시 요청하면 GRACE, 방금 발급한 토큰 쌍을 그대로 돌려준다")
    void rotate_withinGrace() {
        UUID familyId = UUID.randomUUID();
        UUID oldJti = UUID.randomUUID();
        UUID newJti = UUID.randomUUID();
        repository.save(familyId, oldJti, FAMILY_TTL);
        rotate(familyId, oldJti, newJti, "first-access first-refresh");

        RotationResult result = rotate(familyId, oldJti, UUID.randomUUID(), "second-access second-refresh");

        assertThat(result.status()).isEqualTo(RotationResult.Status.GRACE);
        assertThat(result.graceValue()).isEqualTo("first-access first-refresh");
        assertThat(currentJti(familyId)).isEqualTo(newJti.toString());   // 두 번째 요청은 교체하지 않음
    }

    @Test
    @DisplayName("유예 시간이 지난 뒤 옛 jti로 요청하면 REUSED, family가 삭제된다")
    void rotate_afterGrace() throws InterruptedException {
        UUID familyId = UUID.randomUUID();
        UUID oldJti = UUID.randomUUID();
        repository.save(familyId, oldJti, FAMILY_TTL);
        rotate(familyId, oldJti, UUID.randomUUID(), "access refresh");

        Thread.sleep(GRACE_TTL.toMillis() + 500);   // 유예 키 만료 대기
        RotationResult result = rotate(familyId, oldJti, UUID.randomUUID(), "access refresh");

        assertThat(result.status()).isEqualTo(RotationResult.Status.REUSED);
        assertThat(currentJti(familyId)).isNull();
    }

    @Test
    @DisplayName("family 키가 없으면 MISSING")
    void rotate_missing() {
        RotationResult result = rotate(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "access refresh");

        assertThat(result.status()).isEqualTo(RotationResult.Status.MISSING);
    }

    @Test
    @DisplayName("로그아웃 직후에는 유예 키가 남아 있어도 MISSING (로그아웃 우선)")
    void rotate_afterLogout_withinGrace() {
        UUID familyId = UUID.randomUUID();
        UUID oldJti = UUID.randomUUID();
        repository.save(familyId, oldJti, FAMILY_TTL);
        rotate(familyId, oldJti, UUID.randomUUID(), "access refresh");

        repository.delete(familyId);
        RotationResult result = rotate(familyId, oldJti, UUID.randomUUID(), "access refresh");

        assertThat(result.status()).isEqualTo(RotationResult.Status.MISSING);
    }

    @Test
    @DisplayName("같은 토큰으로 동시에 10번 재발급하면 1번만 ROTATED, 나머지는 같은 토큰 쌍을 받는 GRACE")
    void rotate_concurrent() throws Exception {
        UUID familyId = UUID.randomUUID();
        UUID oldJti = UUID.randomUUID();
        repository.save(familyId, oldJti, FAMILY_TTL);

        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RotationResult>> futures = new ArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            String graceValue = "access-" + i + " refresh-" + i;   // 요청마다 다른 토큰 쌍
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await();   // 모든 스레드가 준비되면 동시에 출발
                return rotate(familyId, oldJti, UUID.randomUUID(), graceValue);
            }));
        }
        ready.await();
        start.countDown();

        List<RotationResult> results = new ArrayList<>();
        for (Future<RotationResult> future : futures) {
            results.add(future.get());
        }
        executor.shutdown();

        List<RotationResult> rotated = results.stream()
                .filter(r -> r.status() == RotationResult.Status.ROTATED).toList();
        List<RotationResult> grace = results.stream()
                .filter(r -> r.status() == RotationResult.Status.GRACE).toList();

        assertThat(rotated).hasSize(1);
        assertThat(grace).hasSize(requestCount - 1);
        // 유예로 받은 토큰 쌍이 모두 같아야 함 (= 처음 교체에 성공한 요청의 토큰 쌍)
        assertThat(grace).extracting(RotationResult::graceValue).containsOnly(grace.get(0).graceValue());
    }

    private RotationResult rotate(UUID familyId, UUID presented, UUID newJti, String graceValue) {
        return repository.rotate(familyId, presented, newJti, FAMILY_TTL, graceValue, GRACE_TTL);
    }

    private String currentJti(UUID familyId) {
        return redisTemplate.opsForValue().get(familyKey(familyId));
    }

    private String familyKey(UUID familyId) {
        return "auth:refresh:family:" + familyId;
    }

    private String graceKey(UUID familyId, UUID tokenId) {
        return "auth:refresh:grace:" + familyId + ":" + tokenId;
    }
}