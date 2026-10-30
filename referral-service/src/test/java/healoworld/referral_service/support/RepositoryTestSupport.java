package healoworld.referral_service.support;

import healoworld.referral_service.global.config.JpaAuditingConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 개발 DB와 분리된 Postgres 컨테이너에서 리포지토리 쿼리를 검증한다
// 컨테이너는 모든 테스트 클래스가 공유하고 JVM 종료 시 Testcontainers(Ryuk)가 정리한다
// (@Container로 두면 클래스마다 컨테이너가 재시작되어 캐시된 스프링 컨텍스트가 이전 포트로 접속하게 됨)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({QuerydslConfig.class, JpaAuditingConfig.class})
public abstract class RepositoryTestSupport {

    // TestDatabaseGuard가 실제 연결된 DB 이름을 검증하므로 컨테이너의 DB 이름도 테스트 DB 이름과 맞춘다
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName(TestDatabaseGuard.TEST_DATABASE);

    static {
        POSTGRES.start();
    }
}
