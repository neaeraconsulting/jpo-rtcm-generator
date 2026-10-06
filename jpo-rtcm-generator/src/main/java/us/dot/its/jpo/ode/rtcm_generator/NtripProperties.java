package us.dot.its.jpo.ode.rtcm_generator;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ntrip")
public class NtripProperties {

    private String host;
    private int port = 2101;
    private String mountpoint;
    private String username;
    private String password;
    private int connectTimeoutSec = 15;
    private int socketTimeoutSec = 15;
    private int reconnectDelaySec = 5;
    private int ggaIntervalSec = 20;
    private double fakeLat = 38.9542;
    private double fakeLon = -77.1492;
    private double fakeAltM = 30.0;
    private String outputTopic = "topic.RGRtcmJson";
    private String userAgent = "NTRIP-RTCM-Generator/1.0";
    private boolean preferNtripV2 = true;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getMountpoint() {
        return mountpoint;
    }

    public void setMountpoint(String mountpoint) {
        this.mountpoint = mountpoint;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    public void setConnectTimeoutSec(int connectTimeoutSec) {
        this.connectTimeoutSec = connectTimeoutSec;
    }

    public int getSocketTimeoutSec() {
        return socketTimeoutSec;
    }

    public void setSocketTimeoutSec(int socketTimeoutSec) {
        this.socketTimeoutSec = socketTimeoutSec;
    }

    public int getReconnectDelaySec() {
        return reconnectDelaySec;
    }

    public void setReconnectDelaySec(int reconnectDelaySec) {
        this.reconnectDelaySec = reconnectDelaySec;
    }

    public int getGgaIntervalSec() {
        return ggaIntervalSec;
    }

    public void setGgaIntervalSec(int ggaIntervalSec) {
        this.ggaIntervalSec = ggaIntervalSec;
    }

    public double getFakeLat() {
        return fakeLat;
    }

    public void setFakeLat(double fakeLat) {
        this.fakeLat = fakeLat;
    }

    public double getFakeLon() {
        return fakeLon;
    }

    public void setFakeLon(double fakeLon) {
        this.fakeLon = fakeLon;
    }

    public double getFakeAltM() {
        return fakeAltM;
    }

    public void setFakeAltM(double fakeAltM) {
        this.fakeAltM = fakeAltM;
    }

    public String getOutputTopic() {
        return outputTopic;
    }

    public void setOutputTopic(String outputTopic) {
        this.outputTopic = outputTopic;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isPreferNtripV2() {
        return preferNtripV2;
    }

    public void setPreferNtripV2(boolean preferNtripV2) {
        this.preferNtripV2 = preferNtripV2;
    }

    public void validateConfig() {
        requireText(host, "ntrip.host");
        requireText(mountpoint, "ntrip.mountpoint");
        requireText(username, "ntrip.username");
        requireText(password, "ntrip.password");
        requireText(outputTopic, "ntrip.output-topic");

        if (port <= 0 || port > 65535) {
            throw new IllegalStateException("Invalid ntrip.port: " + port);
        }
        if (connectTimeoutSec <= 0) {
            throw new IllegalStateException("Invalid ntrip.connect-timeout-sec: " + connectTimeoutSec);
        }
        if (socketTimeoutSec <= 0) {
            throw new IllegalStateException("Invalid ntrip.socket-timeout-sec: " + socketTimeoutSec);
        }
        if (reconnectDelaySec < 0) {
            throw new IllegalStateException("Invalid ntrip.reconnect-delay-sec: " + reconnectDelaySec);
        }
        if (ggaIntervalSec <= 0) {
            throw new IllegalStateException("Invalid ntrip.gga-interval-sec: " + ggaIntervalSec);
        }
    }

    private void requireText(String value, String propertyName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalStateException("Missing required property:" + propertyName);
        }
    }
}
