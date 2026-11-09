package healoworld.slot_service.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    // 기준 시간대는 한국 시간이다. 컨테이너 TZ 환경변수가 빠져도 시간대가 바뀌지 않도록 코드에서 고정한다
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Bean
    public Clock clock() {
        return Clock.system(ZONE);
    }
}
