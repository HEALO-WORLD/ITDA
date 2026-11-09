package healoworld.slot_service;

import healoworld.slot_service.support.PostgresContainerSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class SlotServiceApplicationTests extends PostgresContainerSupport {

	@Test
	void contextLoads() {
	}

}
