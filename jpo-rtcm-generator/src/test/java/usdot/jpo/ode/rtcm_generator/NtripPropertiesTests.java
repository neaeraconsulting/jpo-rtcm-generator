package usdot.jpo.ode.rtcm_generator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import us.dot.its.jpo.ode.rtcm_generator.NtripProperties;

class NtripPropertiesTests {

    @Test
    void configRequiresMandatoryFields() {
        NtripProperties properties = new NtripProperties();

        assertThrows(IllegalStateException.class, properties::validateConfig);
    }

    @Test
    void configAcceptsValidValues() {
        NtripProperties properties = new NtripProperties();
        
        properties.setHost("caster.example.org");
        properties.setPort(2101);
        properties.setMountpoint("MOUNT_A");
        properties.setUsername("user");
        properties.setPassword("pass");
        properties.setOutputTopic("topic.RGRtcmJson");

        assertDoesNotThrow(properties::validateConfig);
    }
}
