package com.awad.emailclientai;

import com.awad.emailclientai.shared.config.environment.EnvironmentConfig;
import org.apache.tomcat.util.buf.EncodedSolidusHandling;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;





@SpringBootApplication
@EnableScheduling
public class EmailClientAiApplication {

	public static void main(String[] args) {
        System.setProperty("org.apache.tomcat.util.buf.UDecoder.ALLOW_ENCODED_SLASH", "true");
        SpringApplication app = new SpringApplication(EmailClientAiApplication.class);
        app.addInitializers(new EnvironmentConfig());
        app.run(args);
	}

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatCustomizer() {
        return factory -> factory.addConnectorCustomizers(connector -> {
            connector.setEncodedSolidusHandling(EncodedSolidusHandling.PASS_THROUGH.getValue());
        });
    }

}