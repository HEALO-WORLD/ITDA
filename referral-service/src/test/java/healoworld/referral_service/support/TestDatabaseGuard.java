package healoworld.referral_service.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

// 모든 Spring 테스트(@SpringBootTest, @DataJpaTest 등)가 개발 DB에 연결하지 못하게 막는 안전장치
// META-INF/spring.factories에 등록되어 테스트 클래스마다 자동 적용된다
// 1) 컨텍스트 생성 전: test 프로필이 켜져 있고 접속 URL이 테스트 DB인지 확인 (아니면 DB 연결 자체를 하지 않고 실패)
// 2) 컨텍스트 생성 후: 실제로 연결된 DB 이름(current_database())이 테스트 DB인지 다시 확인
public class TestDatabaseGuard implements ContextCustomizerFactory {

    static final String TEST_PROFILE = "test";
    static final String TEST_DATABASE = "itda_referral_test";

    private static final Logger log = LoggerFactory.getLogger(TestDatabaseGuard.class);

    @Override
    public ContextCustomizer createContextCustomizer(Class<?> testClass,
                                                     List<ContextConfigurationAttributes> configAttributes) {
        return new Guard();
    }

    private static final class Guard implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            ConfigurableEnvironment environment = context.getEnvironment();
            if (!Arrays.asList(environment.getActiveProfiles()).contains(TEST_PROFILE)) {
                throw new IllegalStateException(mergedConfig.getTestClass().getSimpleName()
                        + ": @ActiveProfiles(\"test\")가 없어 개발 DB에 연결될 수 있으므로 테스트를 중단합니다.");
            }
            String url = environment.getProperty("spring.datasource.url");
            if (url == null || !url.endsWith("/" + TEST_DATABASE)) {
                throw new IllegalStateException("테스트 DB(" + TEST_DATABASE + ")가 아닌 곳에 연결하려고 합니다: " + url);
            }
            context.addApplicationListener((ApplicationListener<ContextRefreshedEvent>) event ->
                    verifyConnectedDatabase(event.getApplicationContext().getBeanProvider(DataSource.class)
                            .getIfAvailable()));
        }

        private void verifyConnectedDatabase(DataSource dataSource) {
            if (dataSource == null) {
                return;   // DB를 쓰지 않는 테스트 컨텍스트
            }
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("select current_database()")) {
                resultSet.next();
                String connected = resultSet.getString(1);
                if (!TEST_DATABASE.equals(connected)) {
                    throw new IllegalStateException("테스트가 " + connected + " DB에 연결되었습니다. 테스트를 중단합니다.");
                }
                log.info("테스트 DB 연결 확인: {}", connected);
            } catch (SQLException e) {
                throw new IllegalStateException("테스트 DB 연결 확인에 실패했습니다.", e);
            }
        }

        // 같은 설정의 테스트끼리 Spring 컨텍스트 캐시를 공유할 수 있도록 모든 Guard를 같은 것으로 본다
        @Override
        public boolean equals(Object other) {
            return other instanceof Guard;
        }

        @Override
        public int hashCode() {
            return Guard.class.hashCode();
        }
    }
}
