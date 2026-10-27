package healoworld.referral_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@EnableJpaAuditing
@SpringBootApplication
public class ReferralServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReferralServiceApplication.class, args);
	}

}
