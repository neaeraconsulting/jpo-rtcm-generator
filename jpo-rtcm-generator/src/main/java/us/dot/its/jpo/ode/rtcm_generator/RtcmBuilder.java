package us.dot.its.jpo.ode.rtcm_generator;

import java.time.ZonedDateTime;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.core.JsonProcessingException;
import us.dot.its.jpo.asn.j2735.r2024.Common.DDateTime;
import us.dot.its.jpo.asn.j2735.r2024.Common.DDay;
import us.dot.its.jpo.asn.j2735.r2024.Common.DHour;
import us.dot.its.jpo.asn.j2735.r2024.Common.DMinute;
import us.dot.its.jpo.asn.j2735.r2024.Common.DMonth;
import us.dot.its.jpo.asn.j2735.r2024.Common.DSecond;
import us.dot.its.jpo.asn.j2735.r2024.Common.DYear;
import us.dot.its.jpo.asn.j2735.r2024.Common.Elevation;
import us.dot.its.jpo.asn.j2735.r2024.Common.FullPositionVector;
import us.dot.its.jpo.asn.j2735.r2024.Common.Latitude;
import us.dot.its.jpo.asn.j2735.r2024.Common.Longitude;
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
        metadata.setSource(OdeMessageFrameMetadata.Source.UNKNOWN);
        metadata.setRecordType(RecordType.rtcmTx);
        metadata.setRecordGeneratedBy(OdeMsgMetadata.GeneratedBy.UNKNOWN);
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
            byte[] bytes = codec.xerToUper(xer);
            return HexFormat.of().formatHex(bytes);
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert RTCM corrections to XML", e);
            return null;
        }
    }

    public RTCMcorrectionsMessageFrame buildRtcmCorrectionsMessageFrame(String ntripHexPayload, int messageCount,
            ZonedDateTime timestamp, NtripProperties ntripProperties) {
        RTCMcorrections rtcmCorrections = new RTCMcorrections();
        rtcmCorrections.setMsgCnt(new MsgCount(messageCount));
        rtcmCorrections.setRev(RTCM_Revision.RTCMREV3);
        rtcmCorrections.setMsgs(getRtcmMessageList(ntripHexPayload));
        rtcmCorrections.setAnchorPoint(getAnchorPoint(ntripProperties, timestamp));


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

    public FullPositionVector getAnchorPoint(NtripProperties properties, ZonedDateTime time) {
        FullPositionVector anchorPoint = new FullPositionVector();
        anchorPoint.setLat(getAsJ2735Latitude(properties.getLatitude()));
        anchorPoint.setLong_(getAsJ2735Longitude(properties.getLongitude()));
        anchorPoint.setElevation(getAsJ2735Elevation(properties.getElevation()));
        anchorPoint.setUtcTime(getTimeAsDDateTime(time));
        return anchorPoint;
    }

    public Longitude getAsJ2735Longitude(double longitude) {
        if(longitude > 180.0){
            log.warn("Longitude value {} exceeds maximum of 180.0, setting to 180.0", longitude);
            longitude = 180.0;
        }else if(longitude < -180.0){
            log.warn("Longitude value {} is below minimum of -180.0, setting to -180.0", longitude);
            longitude = -180.0;
        }
        return new Longitude((int)(longitude * 1E7));
    }
    
    public Latitude getAsJ2735Latitude(double latitude) {
        if(latitude > 90.0){
            log.warn("Latitude value {} exceeds maximum of 90.0, setting to 90.0", latitude);
            latitude = 90.0;
        }else if(latitude < -90.0){
            log.warn("Latitude value {} is below minimum of -90.0, setting to -90.0", latitude);
            latitude = -90.0;
        }
        return new Latitude((int)(latitude * 1E7));
    }

    public Elevation getAsJ2735Elevation(double elevation) {
        if(elevation > 6143.9){
            return new Elevation(61439);
        }else if(elevation < -409.5){
            return new Elevation(-4095);
        }else{
            return new Elevation((int)(elevation*10));
        }
    }

    public DDateTime getTimeAsDDateTime(ZonedDateTime time) {
        DDateTime dDateTime = new DDateTime();
        dDateTime.setYear(new DYear(time.getYear()));
        dDateTime.setMonth(new DMonth(time.getMonthValue()));
        dDateTime.setDay(new DDay(time.getDayOfMonth()));
        dDateTime.setHour(new DHour(time.getHour()));
        dDateTime.setMinute(new DMinute(time.getMinute()));
        dDateTime.setSecond(new DSecond(time.getSecond() * 1000 + time.getNano()/1000000));
        return dDateTime;
    }
}

