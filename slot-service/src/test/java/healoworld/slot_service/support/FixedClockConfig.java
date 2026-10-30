package healoworld.slot_service.support;

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
        ZoneId zone = ZoneId.systemDefault();
        return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
    }
}