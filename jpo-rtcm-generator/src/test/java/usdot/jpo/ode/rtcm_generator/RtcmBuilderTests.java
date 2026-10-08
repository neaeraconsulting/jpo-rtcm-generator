package usdot.jpo.ode.rtcm_generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import j2735ffm.MessageFrameCodec;
import us.dot.its.jpo.asn.j2735.r2024.Common.MinuteOfTheYear;
import us.dot.its.jpo.asn.j2735.r2024.Common.RTCMmessageList;
import us.dot.its.jpo.asn.j2735.r2024.RTCMcorrections.RTCM_Revision;
import us.dot.its.jpo.asn.j2735.r2024.RTCMcorrections.RTCMcorrections;
import us.dot.its.jpo.asn.j2735.r2024.RTCMcorrections.RTCMcorrectionsMessageFrame;
import us.dot.its.jpo.ode.model.OdeLogMetadata.RecordType;
import us.dot.its.jpo.ode.model.OdeLogMetadata.SecurityResultCode;
import us.dot.its.jpo.ode.model.OdeLogMsgMetadataLocation;
import us.dot.its.jpo.ode.model.OdeMessageFrameData;
import us.dot.its.jpo.ode.model.OdeMessageFrameMetadata;
import us.dot.its.jpo.ode.model.ReceivedMessageDetails;
import us.dot.its.jpo.ode.model.RxSource;
import us.dot.its.jpo.ode.rtcm_generator.NtripProperties;
import us.dot.its.jpo.ode.rtcm_generator.RtcmBuilder;
import us.dot.its.jpo.ode.util.XmlUtils;

class RtcmBuilderTests {

    private MessageFrameCodec codec;
    private RtcmBuilder builder;

    @BeforeEach
    void setUp() {
        codec = Mockito.mock(MessageFrameCodec.class);
        builder = new RtcmBuilder(codec);
    }

    @Test
    void buildOdeAsn1DataPopulatesMetadata() {
        NtripProperties properties = new NtripProperties();
        properties.setHost("caster.example.org");
        properties.setLatitude(42.34);
        properties.setLongitude(-83.05);
        properties.setElevation(75.0);
        RTCMcorrectionsMessageFrame frame = new RTCMcorrectionsMessageFrame();

        OdeMessageFrameData result = builder.buildOdeAsn1Data(properties, "D30000", frame);

        assertNotNull(result);
        assertNotNull(result.getPayload());
        assertNotNull(result.getMetadata());

        OdeMessageFrameMetadata metadata = (OdeMessageFrameMetadata) result.getMetadata();
        assertEquals("caster.example.org", metadata.getOriginIp());
        assertEquals(OdeMessageFrameMetadata.Source.NA, metadata.getSource());
        assertEquals(RecordType.rtcmTx, metadata.getRecordType());
        assertEquals(SecurityResultCode.success, metadata.getSecurityResultCode());

        ReceivedMessageDetails details = metadata.getReceivedMessageDetails();
        assertNotNull(details);
        assertEquals(RxSource.NA, details.getRxSource());

        OdeLogMsgMetadataLocation location = details.getLocationData();
        assertNotNull(location);
        assertEquals("42.34", location.getLatitude());
        assertEquals("-83.05", location.getLongitude());
        assertEquals("75.0", location.getElevation());
    }

    @Test
    void convertFrameToUperHexReturnsHexEncodedUperBytes() {
        when(codec.xerToUper(anyString())).thenReturn(new byte[] {0x01, 0x23, (byte) 0xAB});
        RTCMcorrectionsMessageFrame frame = new RTCMcorrectionsMessageFrame();

        String result = builder.convertFrameToUperHex(frame);

        assertEquals("0123ab", result);
        verify(codec, times(1)).xerToUper(anyString());
    }

    @Test
    void convertFrameToUperHexReturnsNullWhenXmlSerializationFails() throws Exception {
        XmlUtils xmlUtils = Mockito.mock(XmlUtils.class);
        XmlMapper mapper = Mockito.mock(XmlMapper.class);
        when(xmlUtils.getXmlMapper()).thenReturn(mapper);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("serialization failed") {
            private static final long serialVersionUID = 1L;
        });

        setField(builder, "xmlUtils", xmlUtils);
        RTCMcorrectionsMessageFrame frame = new RTCMcorrectionsMessageFrame();

        String result = builder.convertFrameToUperHex(frame);

        assertNull(result);
    }

    @Test
    void buildRtcmCorrectionsMessageFramePopulatesFields() {
        ZonedDateTime timestamp = ZonedDateTime.of(2026, 1, 1, 0, 1, 0, 0, ZoneOffset.UTC);

        RTCMcorrectionsMessageFrame frame = builder.buildRtcmCorrectionsMessageFrame("A1B2C3", 3, timestamp);

        assertNotNull(frame);
        assertNotNull(frame.getValue());
        RTCMcorrections corrections = frame.getValue();
        assertEquals(RTCM_Revision.RTCMREV3, corrections.getRev());
        assertEquals(new MinuteOfTheYear(1), corrections.getTimeStamp());
        assertEquals(1, corrections.getMsgs().size());
    }

    @Test
    void getCurrentMinuteOfTheYearCalculatesExpectedValue() {
        ZonedDateTime timestamp = ZonedDateTime.of(2026, 1, 1, 0, 1, 0, 0, ZoneOffset.UTC);

        assertEquals(new MinuteOfTheYear(1), builder.getCurrentMinuteOfTheYear(timestamp));
    }

    @Test
    void getRtcmMessageListContainsSingleMessage() {
        RTCMmessageList list = builder.getRtcmMessageList("A1B2C3");

        assertEquals(1, list.size());
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
