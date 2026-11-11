package healoworld.referral_service.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresContainerSupport {

    // 테스트 컨텍스트는 설정 조합마다 캐시되고, 각자 커넥션 풀(기본 10개)을 쥔 채 남는다.
    // Postgres 기본 max_connections(100)로는 통합 테스트가 늘면 too many clients로 컨텍스트 로딩이 실패하므로 여유 있게 올린다
    static final String MAX_CONNECTIONS = "300";

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18")
                    .withDatabaseName(TestDatabaseGuard.TEST_DATABASE)
                    .withCommand("postgres", "-c", "max_connections=" + MAX_CONNECTIONS);

    static {
        POSTGRES.start();
    }
}