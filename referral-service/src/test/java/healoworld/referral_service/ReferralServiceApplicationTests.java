package healoworld.referral_service;

import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ReferralServiceApplicationTests extends PostgresContainerSupport {

    @Test
    void contextLoads() {
    }

}
