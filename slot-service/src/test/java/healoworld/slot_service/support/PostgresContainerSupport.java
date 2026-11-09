package healoworld.slot_service.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 통합 테스트용 Postgres 컨테이너. 로컬 DB(localhost:5432) 없이 Docker만 있으면 테스트가 돈다
// 컨테이너는 모든 테스트 클래스가 공유하고 JVM 종료 시 Testcontainers(Ryuk)가 정리한다
// (@Container로 두면 클래스마다 컨테이너가 재시작되어 캐시된 스프링 컨텍스트가 이전 포트로 접속하게 됨)
// @ServiceConnection이 datasource 접속 정보를 이 컨테이너로 덮어쓴다
public abstract class PostgresContainerSupport {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("slot_test_db");

    static {
        POSTGRES.start();
    }
}
