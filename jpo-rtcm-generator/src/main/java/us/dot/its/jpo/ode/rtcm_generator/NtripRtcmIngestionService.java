package us.dot.its.jpo.ode.rtcm_generator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class NtripRtcmIngestionService {

    private static final Logger logger = LoggerFactory.getLogger(NtripRtcmIngestionService.class);
    private static final DateTimeFormatter GGA_TIME_FORMATTER = DateTimeFormatter.ofPattern("HHmmss");

    private final NtripProperties ntripProperties;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ExecutorService worker;
    private final AtomicBoolean running;

    public NtripRtcmIngestionService(NtripProperties ntripProperties, KafkaTemplate<String, String> kafkaTemplate) {
        this.ntripProperties = ntripProperties;
        this.kafkaTemplate = kafkaTemplate;

        logger.info("Initializing NtripRtcmIngestionService with host: {} and mountpoint: {}",
                ntripProperties.getHost(), ntripProperties.getMountpoint());

        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ntrip-rtcm-worker");
            t.setDaemon(false);
            return t;
        });
        this.running = new AtomicBoolean(false);
    }

    @PostConstruct
    public void start() {

        ntripProperties.validateConfig();
        running.set(true);
        worker.submit(this::runLoop);
        logger.info("NTRIP ingestion started for {}:{}/{} -> topic {}", ntripProperties.getHost(),
                ntripProperties.getPort(), ntripProperties.getMountpoint(), ntripProperties.getOutputTopic());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        worker.shutdownNow();
    }

    private void runLoop() {
        while (running.get()) {
            try {
                streamUntilDisconnect();
            } catch (Exception e) {
                if (running.get()) {
                    logger.warn("NTRIP stream cycle failed: {}", e.getMessage(), e);
                }
            }

            if (!running.get()) {
                break;
            }

            try {
                TimeUnit.SECONDS.sleep(ntripProperties.getReconnectDelaySec());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void streamUntilDisconnect() throws IOException {
        try (ConnectionSession session = connectWithFallback()) {
            sendGga(session.outputStream());

            ScheduledExecutorService heartbeat = startGgaHeartbeat(session.outputStream());
            try {
                readAndPublishStream(session);
            } finally {
                heartbeat.shutdownNow();
            }
        }
    }

    private ConnectionSession connectWithFallback() throws IOException {
        IOException v2Error = null;

        if (ntripProperties.isPreferNtripV2()) {
            try {
                return connect(true);
            } catch (IOException e) {
                v2Error = e;
                logger.warn("NTRIP 2.0 request failed, falling back to legacy request: {}", e.getMessage());
            }
        }

        try {
            return connect(false);
        } catch (IOException legacyError) {
            if (v2Error != null) {
                legacyError.addSuppressed(v2Error);
            }
            throw legacyError;
        }
    }

    private ConnectionSession connect(boolean ntripV2) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(ntripProperties.getHost(), ntripProperties.getPort()),
                ntripProperties.getConnectTimeoutSec() * 1000);
        socket.setSoTimeout(ntripProperties.getSocketTimeoutSec() * 1000);

        try {
            OutputStream outputStream = socket.getOutputStream();
            InputStream inputStream = socket.getInputStream();

            String request = buildRequest(ntripV2);
            outputStream.write(request.getBytes(StandardCharsets.US_ASCII));
            outputStream.flush();

            HeaderResponse headerResponse = readHeaderResponse(inputStream);
            if (!headerResponse.success()) {
                throw new IOException("NTRIP caster rejected request: " + headerResponse.statusLine());
            }

            logger.info("Connected to NTRIP caster with {} request style: {}", ntripV2 ? "NTRIP 2.0" : "legacy",
                    headerResponse.statusLine());

            return new ConnectionSession(socket, inputStream, outputStream, headerResponse.remainingBytes());
        } catch (Exception e) {
            try {
                socket.close();
            } catch (IOException closeErr) {
                e.addSuppressed(closeErr);
            }
            if (e instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to initialize NTRIP connection", e);
        }
    }

    private String buildRequest(boolean ntripV2) {
        String auth =
                Base64.getEncoder().encodeToString((ntripProperties.getUsername() + ":" + ntripProperties.getPassword())
                        .getBytes(StandardCharsets.US_ASCII));

        if (ntripV2) {
            return "GET /" + ntripProperties.getMountpoint() + " HTTP/1.1\r\n" + "Host: " + ntripProperties.getHost()
                    + ":" + ntripProperties.getPort() + "\r\n" + "User-Agent: " + ntripProperties.getUserAgent()
                    + "\r\n" + "Ntrip-Version: Ntrip/2.0\r\n" + "Connection: close\r\n" + "Authorization: Basic " + auth
                    + "\r\n" + "\r\n";
        }

        return "GET /" + ntripProperties.getMountpoint() + " HTTP/1.0\r\n" + "User-Agent: "
                + ntripProperties.getUserAgent() + "\r\n" + "Authorization: Basic " + auth + "\r\n" + "\r\n";
    }

    private HeaderResponse readHeaderResponse(InputStream inputStream) throws IOException {
        ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int headerEnd;

        while (true) {
            int read = inputStream.read(chunk);
            if (read == -1) {
                throw new IOException("Caster closed connection before sending headers");
            }
            responseBytes.write(chunk, 0, read);

            byte[] all = responseBytes.toByteArray();
            headerEnd = findHeaderEnd(all);
            if (headerEnd >= 0) {
                String headers = new String(all, 0, headerEnd, StandardCharsets.US_ASCII);
                String statusLine = headers.lines().findFirst().orElse("UNKNOWN");
                boolean success = statusLine.contains("200");
                byte[] remaining = new byte[all.length - (headerEnd + 4)];
                System.arraycopy(all, headerEnd + 4, remaining, 0, remaining.length);
                return new HeaderResponse(statusLine, success, remaining);
            }

            if (responseBytes.size() > 16384) {
                throw new IOException("Header too large from caster");
            }
        }
    }

    private int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private ScheduledExecutorService startGgaHeartbeat(OutputStream outputStream) {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ntrip-gga-heartbeat");
            t.setDaemon(false);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            if (!running.get()) {
                return;
            }
            try {
                sendGga(outputStream);
            } catch (IOException e) {
                logger.warn("Failed to send GGA heartbeat: {}", e.getMessage());
            }
        }, ntripProperties.getGgaIntervalSec(), ntripProperties.getGgaIntervalSec(), TimeUnit.SECONDS);

        return scheduler;
    }

    private void sendGga(OutputStream outputStream) throws IOException {
        String ggaSentence =
                makeGga(ntripProperties.getLatitude(), ntripProperties.getLongitude(), ntripProperties.getElevation());

        System.out.println("Sending GGA sentence: " + ggaSentence);

        outputStream.write(ggaSentence.getBytes(StandardCharsets.US_ASCII));
        outputStream.flush();
    }

    private void readAndPublishStream(ConnectionSession session) throws IOException {
        byte[] pending = Objects.requireNonNullElse(session.initialPayload(), new byte[0]);
        if (pending.length > 0) {
            pending = publishParsedFrames(pending);
        }

        byte[] buffer = new byte[4096];
        while (running.get()) {
            try {
                int read = session.inputStream().read(buffer);
                if (read == -1) {
                    throw new IOException(
                            "Caster closed the stream. Possible causes include:\r\n network issues\r\ncaster shutdown\r\nincorrect mountpoint configuration\r\ninvalid credentials\r\nno gga stream provided\r\ngga stream is in an invalid location");
                }

                byte[] merged = new byte[pending.length + read];
                System.arraycopy(pending, 0, merged, 0, pending.length);
                System.arraycopy(buffer, 0, merged, pending.length, read);
                pending = publishParsedFrames(merged);
            } catch (java.net.SocketTimeoutException e) {
                // Socket timeout is expected for idle periods. Continue waiting for frames.
            }
        }
    }

    private byte[] publishParsedFrames(byte[] data) {
        RtcmFrameParser.ParseResult parseResult = RtcmFrameParser.parse(data);
        for (RtcmFrameParser.RtcmFrame frame : parseResult.frames()) {
            publishFrame(frame);
        }
        return parseResult.remaining();
    }

    private void publishFrame(RtcmFrameParser.RtcmFrame frame) {
        String payloadHex = HexFormat.of().withUpperCase().formatHex(frame.frameBytes());
        RtcmBuilder.buildAsn1DataFromPayload(ntripProperties.getHost(), ntripProperties.getPort(),
                ntripProperties.getMountpoint(), payloadHex, ntripProperties.latitude, ntripProperties.getLongitude(),
                ntripProperties.getElevation());
        String message = toJson(frame, payloadHex);
        String key = frame.messageType() >= 0 ? Integer.toString(frame.messageType()) : "unknown";
        kafkaTemplate.send(ntripProperties.getOutputTopic(), key, message);
    }

    private String toJson(RtcmFrameParser.RtcmFrame frame, String payloadHex) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonField(sb, "timestamp", Instant.now().toString(), true);
        appendJsonField(sb, "casterHost", ntripProperties.getHost(), true);
        appendJsonNumber(sb, "casterPort", ntripProperties.getPort(), true);
        appendJsonField(sb, "mountpoint", ntripProperties.getMountpoint(), true);
        appendJsonNumber(sb, "messageType", frame.messageType(), true);
        appendJsonField(sb, "payloadHex", payloadHex, true);
        appendJsonNumber(sb, "payloadLengthBytes", frame.payloadLength(), false);
        sb.append('}');

        System.out.println("Generated JSON message: " + sb.toString());
        return sb.toString();
    }

    private void appendJsonField(StringBuilder sb, String key, String value, boolean trailingComma) {
        sb.append('"').append(escapeJson(key)).append('"').append(':').append('"').append(escapeJson(value))
                .append('"');
        if (trailingComma) {
            sb.append(',');
        }
    }

    private void appendJsonNumber(StringBuilder sb, String key, int value, boolean trailingComma) {
        sb.append('"').append(escapeJson(key)).append('"').append(':').append(value);
        if (trailingComma) {
            sb.append(',');
        }
    }

    private String escapeJson(String value) {
        String input = value == null ? "" : value;
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t",
                "\\t");
    }

    private String makeGga(double lat, double lon, double altMeters) {
        String latHemisphere = lat >= 0 ? "N" : "S";
        String lonHemisphere = lon >= 0 ? "E" : "W";

        double absLat = Math.abs(lat);
        double absLon = Math.abs(lon);

        int latDegrees = (int) absLat;
        int lonDegrees = (int) absLon;

        double latMinutes = (absLat - latDegrees) * 60.0;
        double lonMinutes = (absLon - lonDegrees) * 60.0;

        String latField = String.format(Locale.US, "%02d%07.4f", latDegrees, latMinutes);
        String lonField = String.format(Locale.US, "%03d%07.4f", lonDegrees, lonMinutes);
        String utcTime = GGA_TIME_FORMATTER.format(Instant.now().atOffset(ZoneOffset.UTC));

        String body = String.format(Locale.US, "GPGGA,%s,%s,%s,%s,%s,4,28,1.0,%.1f,M,0.0,M,,", utcTime, latField,
                latHemisphere, lonField, lonHemisphere, altMeters);

        return "$" + body + "*" + nmeaChecksum(body) + "\r\n";
    }

    private String nmeaChecksum(String sentenceBody) {
        int checksum = 0;
        for (int i = 0; i < sentenceBody.length(); i++) {
            checksum ^= sentenceBody.charAt(i);
        }
        return String.format(Locale.US, "%02X", checksum);
    }

    private record HeaderResponse(String statusLine, boolean success, byte[] remainingBytes) {
    }

    private record ConnectionSession(Socket socket, InputStream inputStream, OutputStream outputStream,
            byte[] initialPayload) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
