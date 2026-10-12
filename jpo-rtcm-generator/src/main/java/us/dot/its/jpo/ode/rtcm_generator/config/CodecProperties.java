package us.dot.its.jpo.ode.rtcm_generator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "j2735.codec")
public class CodecProperties {
    private long textBufferSize;
    private long uperBufferSize;
    private long errorBufferSize;
    private String libraryPath;
    private String windowsLibraryPath;

    public long getTextBufferSize() {
        return textBufferSize;
    }

    public void setTextBufferSize(long textBufferSize) {
        this.textBufferSize = textBufferSize;
    }

    public long getUperBufferSize() {
        return uperBufferSize;
    }

    public void setUperBufferSize(long uperBufferSize) {
        this.uperBufferSize = uperBufferSize;
    }

    public long getErrorBufferSize() {
        return errorBufferSize;
    }

    public void setErrorBufferSize(long errorBufferSize) {
        this.errorBufferSize = errorBufferSize;
    }

    public String getLibraryPath() {
        return libraryPath;
    }

    public void setLibraryPath(String libraryPath) {
        this.libraryPath = libraryPath;
    }

    public String getWindowsLibraryPath() {
        return windowsLibraryPath;
    }

    public void setWindowsLibraryPath(String windowsLibraryPath) {
        this.windowsLibraryPath = windowsLibraryPath;
    }

}
