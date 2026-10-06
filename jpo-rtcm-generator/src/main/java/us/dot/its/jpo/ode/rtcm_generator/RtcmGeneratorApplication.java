package us.dot.its.jpo.ode.rtcm_generator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @Log4j2
@SpringBootApplication
@EnableConfigurationProperties(RtcmGeneratorProperties.class)
public class RtcmGeneratorApplication {



	public static void main(String[] args) {
		SpringApplication.run(RtcmGeneratorApplication.class, args);
		// log.info("RtcmGeneratorApplication Started.");
	}

}
