package healoworld.referral_service.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

// 현재 시각의 기준 (slot-service ClockConfig와 같은 구조)
// - 기준 시간대는 한국 시간이다. JVM 기본 시간대(TZ 환경변수·TimeZone.setDefault)가 달라도 비즈니스 판단이 바뀌지 않도록 코드에서 고정한다
// - 예약·슬롯·장애 시각은 모두 한국 시간 LocalDateTime으로 저장·전달되므로, 이것과 비교하는 현재 시각도 같은 시간대여야 한다
// - 테스트는 Clock.fixed(...)로 바꿔 경계 시각을 검증한다
@Configuration
public class ClockConfig {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
