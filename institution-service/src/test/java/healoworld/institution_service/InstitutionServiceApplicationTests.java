package healoworld.institution_service;

import healoworld.institution_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class InstitutionServiceApplicationTests extends PostgresContainerSupport {

    @Test
    void contextLoads() {
    }

}
