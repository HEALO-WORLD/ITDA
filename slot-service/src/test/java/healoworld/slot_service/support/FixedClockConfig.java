package healoworld.slot_service.support;

import healoworld.slot_service.global.config.ClockConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

@TestConfiguration
public class FixedClockConfig {

    public static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 10, 0);

    @Bean
    @Primary
    public Clock fixedClock() {
        // 운영 Clock과 같은 한국 시간 기준으로 고정한다
        ZoneId zone = ClockConfig.ZONE;
        return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
    }
}
