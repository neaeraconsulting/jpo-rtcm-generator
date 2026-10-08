package us.dot.its.jpo.ode.rtcm_generator;

import java.time.ZonedDateTime;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import us.dot.its.jpo.asn.j2735.r2024.Common.MinuteOfTheYear;
import us.dot.its.jpo.asn.j2735.r2024.Common.MsgCount;
import us.dot.its.jpo.asn.j2735.r2024.Common.RTCMmessage;
import us.dot.its.jpo.asn.j2735.r2024.Common.RTCMmessageList;
import us.dot.its.jpo.asn.j2735.r2024.RTCMcorrections.RTCM_Revision;
import us.dot.its.jpo.asn.j2735.r2024.RTCMcorrections.RTCMcorrections;
import us.dot.its.jpo.asn.j2735.r2024.RTCMcorrections.RTCMcorrectionsMessageFrame;
import us.dot.its.jpo.ode.model.OdeLogMetadata.RecordType;
import us.dot.its.jpo.ode.model.OdeLogMetadata.SecurityResultCode;
import us.dot.its.jpo.ode.model.OdeLogMsgMetadataLocation;
import us.dot.its.jpo.ode.model.OdeMessageFrameData;
import us.dot.its.jpo.ode.model.OdeMessageFrameMetadata;
import us.dot.its.jpo.ode.model.OdeMessageFramePayload;
import us.dot.its.jpo.ode.model.OdeMsgMetadata;
import us.dot.its.jpo.ode.model.ReceivedMessageDetails;
import us.dot.its.jpo.ode.model.RxSource;
import us.dot.its.jpo.ode.util.DateTimeUtils;
import us.dot.its.jpo.ode.util.XmlUtils;
import j2735ffm.MessageFrameCodec;

@Component
public final class RtcmBuilder {

    private final Logger log = LoggerFactory.getLogger(RtcmBuilder.class);
    MessageFrameCodec codec;
    XmlUtils xmlUtils;


    @Autowired
    public RtcmBuilder(MessageFrameCodec codec) {
        this.codec = codec;
        this.xmlUtils = new XmlUtils();
    }

    public OdeMessageFrameData buildOdeAsn1Data(NtripProperties ntripProperties, String asn1Hex,
            RTCMcorrectionsMessageFrame frame) {
        log.debug("Packet received from {}", ntripProperties.getHost());

        // Create OdeMsgPayload and OdeLogMetadata objects and populate them
        // OdeAsn1Payload payload = new OdeAsn1Payload(HexUtils.fromHexString(asn1Hex));

        // OdeMsgPayload<RTCMcorrectionsMessageFrame> payload = new OdeMsgPayload<RTCMcorrectionsMessageFrame>(frame);
        OdeMessageFramePayload payload = new OdeMessageFramePayload(frame);


        OdeMessageFrameMetadata metadata = new OdeMessageFrameMetadata(); // payload

        // Add header data for the decoding process
        metadata.setOdeReceivedAt(DateTimeUtils.now());
        metadata.setOriginIp(ntripProperties.getHost());
        metadata.setSource(OdeMessageFrameMetadata.Source.NA);
        metadata.setRecordType(RecordType.rtcmTx);
        metadata.setRecordGeneratedBy(OdeMsgMetadata.GeneratedBy.TMC);
        metadata.setSecurityResultCode(SecurityResultCode.success);
        metadata.setLogFileName("");
        metadata.setRecordGeneratedAt("");
        metadata.setOdePacketID("");
        metadata.setOdeTimStartDateTime("");
        metadata.setCertPresent(false);
        metadata.setAsn1(asn1Hex);



        ReceivedMessageDetails receivedMessageDetails = new ReceivedMessageDetails();
        OdeLogMsgMetadataLocation locationData = new OdeLogMsgMetadataLocation(
                String.valueOf(ntripProperties.getLatitude()), String.valueOf(ntripProperties.getLongitude()),
                String.valueOf(ntripProperties.getElevation()), "unavailable", "unavailable");
        receivedMessageDetails.setRxSource(RxSource.NA);
        receivedMessageDetails.setLocationData(locationData);
        metadata.setReceivedMessageDetails(receivedMessageDetails);

        return new OdeMessageFrameData(metadata, payload);
        // return new OdeData<OdeMessageFrameMetadata, OdeMessageFramePayload>(metadata, payload);
    }

    public String convertFrameToUperHex(RTCMcorrectionsMessageFrame frame) {

        try {
            String xer = xmlUtils.getXmlMapper().writeValueAsString(frame);
            System.out.println(xer);
            byte[] bytes = codec.xerToUper(xer);
            return HexFormat.of().formatHex(bytes);
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert RTCM corrections to XML", e);
            return null;
        }
    }

    public RTCMcorrectionsMessageFrame buildRtcmCorrectionsMessageFrame(String ntripHexPayload, int messageCount,
            ZonedDateTime timestamp) {
        RTCMcorrections rtcmCorrections = new RTCMcorrections();
        rtcmCorrections.setMsgCnt(new MsgCount(messageCount));
        rtcmCorrections.setRev(RTCM_Revision.RTCMREV3);
        rtcmCorrections.setTimeStamp(getCurrentMinuteOfTheYear(timestamp));
        rtcmCorrections.setMsgs(getRtcmMessageList(ntripHexPayload));


        RTCMcorrectionsMessageFrame frame = new RTCMcorrectionsMessageFrame();
        frame.setValue(rtcmCorrections);

        return frame;

    }

    public MinuteOfTheYear getCurrentMinuteOfTheYear(ZonedDateTime refTime) {
        ZonedDateTime yearStart = refTime.withDayOfYear(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        int minuteOfYear = (int) java.time.Duration.between(yearStart, refTime).toMinutes();
        return new MinuteOfTheYear(minuteOfYear);
    }

    public RTCMmessageList getRtcmMessageList(String ntripHexPayload) {
        RTCMmessageList messageList = new RTCMmessageList();
        messageList.add(new RTCMmessage(ntripHexPayload));
        return messageList;
    }

    // public FullPositionVector getAnchorPoint(ZonedDateTime timestamp, NtripProperties ntripProperties) {
    // FullPositionVector anchorPoint = new FullPositionVector();
    // anchorPoint.setUtcTime(getAsJ2735DDateTime(timestamp));
    // anchorPoint.setLat(getAsJ2735Latitude(ntripProperties.getLatitude()));
    // anchorPoint.setLong_(getAsJ2735Longitude(ntripProperties.getLongitude()));
    // anchorPoint.setElevation(getAsJ2735Elevation(ntripProperties.getElevation()));
    // anchorPoint.setHeading(new Heading(0));
    // anchorPoint.setSpeed(new TransmissionAndSpeed());

    // return anchorPoint;
    // }

    // public DDateTime getAsJ2735DDateTime(ZonedDateTime timestamp) {
    // DDateTime dDateTime = new DDateTime();
    // dDateTime.setYear(new DYear(timestamp.getYear()));
    // dDateTime.setMonth(new DMonth(timestamp.getMonthValue()));
    // dDateTime.setDay(new DDay(timestamp.getDayOfMonth()));
    // dDateTime.setHour(new DHour(timestamp.getHour()));
    // dDateTime.setMinute(new DMinute(timestamp.getMinute()));
    // dDateTime.setSecond(new DSecond(timestamp.getSecond()));
    // return dDateTime;
    // }

    // public Latitude getAsJ2735Latitude(double latitute) {
    // long j2735Latitude = Math.round(latitute * 10E7);
    // return new Latitude(j2735Latitude);
    // }

    // public Longitude getAsJ2735Longitude(double longitude) {
    // long j2735Longitude = Math.round(longitude * 10E7);
    // return new Longitude(j2735Longitude);
    // }

    // public Elevation getAsJ2735Elevation(double elevation) {
    // long j2735Elevation = Math.round(elevation * 10E2);
    // return new Elevation(j2735Elevation);
    // }
}

