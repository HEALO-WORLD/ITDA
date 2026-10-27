package healoworld.institution_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class InstitutionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(InstitutionServiceApplication.class, args);
	}

}
