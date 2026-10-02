package us.dot.its.jpo.ode.rtcm_generator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.UUID;

import jakarta.annotation.PostConstruct;

import org.apache.commons.lang3.SystemUtils;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.processor.LogAndSkipOnInvalidTimestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import lombok.Getter;
import lombok.Setter;
import lombok.AccessLevel;

@Getter
@Setter
@ConfigurationProperties()
public class RtcmGeneratorProperties implements EnvironmentAware {

   private static final Logger logger = LoggerFactory.getLogger(RtcmGeneratorProperties.class);

   // Confluent Properties
   private boolean confluentCloudEnabled = false;
   private String confluentKey = null;
   private String confluentSecret = null;

   private int lingerMs = 0;

   @Autowired
   @Setter(AccessLevel.NONE)
   private Environment env;

   private String version;

   @Setter(AccessLevel.NONE)
   private String kafkaBrokers = null;

   @Setter(AccessLevel.NONE)
   private String hostId;

   @Setter(AccessLevel.NONE)
   private String kafkaBrokerIP = null;

   @PostConstruct
   void initialize() {

      String hostname;
      try {
         hostname = InetAddress.getLocalHost().getHostName();
      } catch (UnknownHostException e) {
         // Let's just use a random hostname
         hostname = UUID.randomUUID().toString();
         logger.info("Unknown host error: {}, using random", e);
      }
      hostId = hostname;
      logger.info("Host ID: {}", hostId);

      if (kafkaBrokers == null) {

         String kafkaBrokers = getEnvironmentVariable("KAFKA_BOOTSTRAP_SERVERS");

         logger.info("ode.kafkaBrokers property not defined. Will try KAFKA_BOOTSTRAP_SERVERS => {}", kafkaBrokers);

         if (kafkaBrokers == null) {
            logger.warn(
                  "Neither ode.kafkaBrokers ode property nor KAFKA_BOOTSTRAP_SERVERS environment variable are defined. Defaulting to localhost:9092");
            kafkaBrokers = "localhost:9092";
         }

      }

      String kafkaType = getEnvironmentVariable("KAFKA_TYPE");
      if (kafkaType != null) {
         confluentCloudEnabled = kafkaType.equals("CONFLUENT");
         if (confluentCloudEnabled) {

            logger.info("Enabling Confluent Cloud Integration");

            confluentKey = getEnvironmentVariable("CONFLUENT_KEY");
            confluentSecret = getEnvironmentVariable("CONFLUENT_SECRET");
         }
      }
   }

   public Properties createStreamProperties(String name) {
      Properties streamProps = new Properties();
      streamProps.put(StreamsConfig.APPLICATION_ID_CONFIG, name);

      streamProps.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);

      streamProps.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
            LogAndContinueExceptionHandler.class.getName());

      streamProps.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG,
            LogAndSkipOnInvalidTimestamp.class.getName());

      streamProps.put(StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG,
            AlwaysContinueProductionExceptionHandler.class.getName());

      streamProps.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 2);

      // streamProps.put(StreamsConfig.producerPrefix("acks"), "all");
      streamProps.put(StreamsConfig.producerPrefix(ProducerConfig.ACKS_CONFIG), "all");

      // Reduce cache buffering per topology to 1MB
      streamProps.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 1 * 1024 * 1024L);

      // Decrease default commit interval. Default for 'at least once' mode of 30000ms
      // is too slow.
      streamProps.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 100);

      // All the keys are Strings in this app
      streamProps.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());

      // Configure the state store location
      if (SystemUtils.IS_OS_LINUX) {
         streamProps.put(StreamsConfig.STATE_DIR_CONFIG, "/var/lib/ode/kafka-streams");
      } else if (SystemUtils.IS_OS_WINDOWS) {
         streamProps.put(StreamsConfig.STATE_DIR_CONFIG, "C:/temp/ode");
      }

      // Increase max.block.ms and delivery.timeout.ms for streams
      final int FIVE_MINUTES_MS = 5 * 60 * 1000;
      streamProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, FIVE_MINUTES_MS);
      streamProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, FIVE_MINUTES_MS);

      streamProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "zstd");
      streamProps.put(ProducerConfig.LINGER_MS_CONFIG, getKafkaLingerMs());

      streamProps.put("cleanup.policy", "compact,delete");
      streamProps.put("log.retention.ms", "7200000"); // 2-hour retention
      streamProps.put("log.segment.bytes", "1048576"); // 1 MB block size

      if (confluentCloudEnabled) {
         streamProps.put("ssl.endpoint.identification.algorithm", "https");
         streamProps.put("security.protocol", "SASL_SSL");
         streamProps.put("sasl.mechanism", "PLAIN");

         if (confluentKey != null && confluentSecret != null) {
            String auth = "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                  "username=\"" + confluentKey + "\" " +
                  "password=\"" + confluentSecret + "\";";
            streamProps.put("sasl.jaas.config", auth);
         } else {
            logger.error(
                  "Environment variables CONFLUENT_KEY and CONFLUENT_SECRET are not set. Set these in the .env file to use Confluent Cloud");
         }
      }

      return streamProps;
   }

   public String getProperty(String key) {
      return env.getProperty(key);
   }

   public String getProperty(String key, String defaultValue) {
      return env.getProperty(key, defaultValue);
   }

   public Object getProperty(String key, int i) {
      return env.getProperty(key, Integer.class, i);
   }

   public Boolean getConfluentCloudStatus() {
      return confluentCloudEnabled;
   }

   @Value("${spring.kafka.bootstrap-servers}")
   public void setKafkaBrokers(String kafkaBrokers) {
      this.kafkaBrokers = kafkaBrokers;
   }

   @Override
   public void setEnvironment(Environment environment) {
      env = environment;
   }

   @Value("${kafka.linger_ms}")
   public void setKafkaLingerMs(int lingerMs) {
      this.lingerMs = lingerMs;
   }

   public int getKafkaLingerMs() {
      return lingerMs;
   }

   private static String getEnvironmentVariable(String variableName) {
      String value = System.getenv(variableName);
      if (value == null || value.equals("")) {
         logger.warn("Something went wrong retrieving the environment variable " + variableName);
      }
      return value;
   }
}
