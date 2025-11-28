package com.awad.emailclientai;

import com.awad.emailclientai.shared.config.environment.EnvironmentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EmailClientAiApplication {

	public static void main(String[] args) {
        SpringApplication app = new SpringApplication(EmailClientAiApplication.class);
        app.addInitializers(new EnvironmentConfig());
        app.run(args);
	}

}
