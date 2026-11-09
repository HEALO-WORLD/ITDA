package healoworld.gateway_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
public class GatewayServiceApplication {

    public static void main(String[] args) {
        // 기준 시간대는 한국 시간이다. 컨테이너 TZ 환경변수가 빠져도 로그·now() 시각이 바뀌지 않도록 고정한다
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("Asia/Seoul")));
        SpringApplication.run(GatewayServiceApplication.class, args);
    }

}
