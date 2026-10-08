package us.dot.its.jpo.ode.rtcm_generator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import j2735ffm.MessageFrameCodec;
import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class CodecConfig {
    private final CodecProperties properties;

    public CodecConfig(CodecProperties properties) {
        this.properties = properties;
    }

    @Bean
    public MessageFrameCodec messageFrameCodec() {
        String libResource =
                System.getProperty("os.name").toLowerCase().contains("win") ? properties.getWindowsLibraryPath()
                        : properties.getLibraryPath();
        Path libPath = Paths.get(libResource);
        return new MessageFrameCodec(properties.getTextBufferSize(), properties.getUperBufferSize(),
                properties.getErrorBufferSize(), libPath);


    }
}
