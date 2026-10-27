package healoworld.slot_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class SlotServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(SlotServiceApplication.class, args);
	}

}
