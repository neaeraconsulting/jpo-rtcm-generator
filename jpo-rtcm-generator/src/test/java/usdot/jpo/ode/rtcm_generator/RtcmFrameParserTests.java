package usdot.jpo.ode.rtcm_generator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;

import us.dot.its.jpo.ode.rtcm_generator.RtcmFrameParser;

class RtcmFrameParserTests {

    @Test
    void parsesSingleFrameAndExtractsMessageType() {
        byte[] frame = makeFrame(1074, new byte[] { 0x11, 0x22, 0x33 });

        RtcmFrameParser.ParseResult result = RtcmFrameParser.parse(frame);

        assertEquals(1, result.frames().size());
        assertEquals(1074, result.frames().getFirst().messageType());
        assertEquals(5, result.frames().getFirst().payloadLength());
        assertEquals(0, result.remaining().length);
    }

    @Test
    void parsesConcatenatedFramesAndSkipsNoiseBytes() {
        byte[] frameA = makeFrame(1006, new byte[] { 0x01, 0x02 });
        byte[] frameB = makeFrame(1124, new byte[] { 0x03 });

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x00);
        output.write(0x7F);
        output.writeBytes(frameA);
        output.write(0x55);
        output.writeBytes(frameB);

        RtcmFrameParser.ParseResult result = RtcmFrameParser.parse(output.toByteArray());

        assertEquals(2, result.frames().size());
        assertEquals(1006, result.frames().get(0).messageType());
        assertEquals(1124, result.frames().get(1).messageType());
        assertArrayEquals(new byte[0], result.remaining());
    }

    @Test
    void keepsIncompleteTrailingFrameInRemainingBuffer() {
        byte[] frame = makeFrame(1094, new byte[] { 0x44, 0x55, 0x66 });
        byte[] partial = new byte[frame.length - 2];
        System.arraycopy(frame, 0, partial, 0, partial.length);

        RtcmFrameParser.ParseResult result = RtcmFrameParser.parse(partial);

        assertEquals(0, result.frames().size());
        assertArrayEquals(partial, result.remaining());
    }

    private byte[] makeFrame(int messageType, byte[] payloadTail) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write((messageType >> 4) & 0xFF);
        payload.write((messageType & 0x0F) << 4);
        payload.writeBytes(payloadTail);

        byte[] payloadBytes = payload.toByteArray();
        int payloadLength = payloadBytes.length;

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0xD3);
        frame.write((payloadLength >> 8) & 0x3F);
        frame.write(payloadLength & 0xFF);
        frame.writeBytes(payloadBytes);

        // Parser currently treats CRC bytes as opaque trailer bytes.
        frame.write(0x00);
        frame.write(0x00);
        frame.write(0x00);

        return frame.toByteArray();
    }
}
