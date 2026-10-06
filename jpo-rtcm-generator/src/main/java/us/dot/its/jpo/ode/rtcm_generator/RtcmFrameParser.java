package us.dot.its.jpo.ode.rtcm_generator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class RtcmFrameParser {

    private RtcmFrameParser() {
    }

    public static ParseResult parse(byte[] data) {
        List<RtcmFrame> frames = new ArrayList<>();
        int offset = 0;

        while (offset + 3 <= data.length) {
            if ((data[offset] & 0xFF) != 0xD3) {
                offset++;
                continue;
            }

            int payloadLength = ((data[offset + 1] & 0x3F) << 8) | (data[offset + 2] & 0xFF);
            int frameSize = 3 + payloadLength + 3;

            if (offset + frameSize > data.length) {
                break;
            }

            int messageType = -1;
            if (payloadLength >= 2) {
                messageType = ((data[offset + 3] & 0xFF) << 4) | ((data[offset + 4] & 0xF0) >> 4);
                messageType &= 0x0FFF;
            }

            byte[] frameBytes = Arrays.copyOfRange(data, offset, offset + frameSize);
            frames.add(new RtcmFrame(messageType, payloadLength, frameBytes));
            offset += frameSize;
        }

        byte[] remaining = Arrays.copyOfRange(data, offset, data.length);
        return new ParseResult(frames, remaining);
    }

    public record RtcmFrame(int messageType, int payloadLength, byte[] frameBytes) {
    }

    public record ParseResult(List<RtcmFrame> frames, byte[] remaining) {
    }
}
