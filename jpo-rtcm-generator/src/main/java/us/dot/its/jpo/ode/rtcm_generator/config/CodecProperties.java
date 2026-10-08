package us.dot.its.jpo.ode.rtcm_generator.config;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "j2735.codec")
@Data
@Setter
@Getter
public class CodecProperties {
    long textBufferSize;
    long uperBufferSize;
    long errorBufferSize;
    String libraryPath;
    String windowsLibraryPath;

}
