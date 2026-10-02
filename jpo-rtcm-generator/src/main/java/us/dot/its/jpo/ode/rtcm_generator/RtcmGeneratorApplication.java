package us.dot.its.jpo.ode.rtcm_generator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
// import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
// @ConfigurationPropertiesScan
@EnableConfigurationProperties(RtcmGeneratorProperties.class)
public class RtcmGeneratorApplication {

	public static void main(String[] args) {
		SpringApplication.run(RtcmGeneratorApplication.class, args);
	}

}
