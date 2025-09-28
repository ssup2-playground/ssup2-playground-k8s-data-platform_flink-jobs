package ssup2.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Properties;

public class MediaWikiPageCreateCounterJob {
    public static void main(String[] args) throws Exception {
        // Setup Flink execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        // Set Kafka properties
        Properties kafkaProps = new Properties();
        kafkaProps.setProperty("security.protocol", "SASL_PLAINTEXT");
        kafkaProps.setProperty("sasl.mechanism", "PLAIN");
        kafkaProps.setProperty("sasl.jaas.config", 
                "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                "username=\"user\" password=\"user\";");

        // Define Kafka source using the new KafkaSource API
        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers("kafka.kafka:9092")
                .setTopics("mediawiki.page-create")
                .setGroupId("page-create-counter")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .setProperties(kafkaProps)
                .build();

        // Create a stream from Kafka source
        DataStream<String> kafkaStream = env.fromSource(
                source,
                WatermarkStrategy.noWatermarks(),
                "Kafka Source"
        );

        ObjectMapper mapper = new ObjectMapper();

        // Parse JSON and filter
        DataStream<Long> validPageCreate = kafkaStream
                .map(json -> {
                    JsonNode root = mapper.readTree(json);
                    String timestamp = root.path("meta").path("dt").asText();
                    int namespace = root.path("page_namespace").asInt();
                    boolean isRedirect = root.path("page_is_redirect").asBoolean();
                    
                    // main namespace(0) and not redirect
                    if (namespace == 0 && !isRedirect) {
                        return Instant.parse(timestamp).toEpochMilli();
                    }
                    return null;
                })
                .filter(timestamp -> timestamp != null);

        // Aggregate counts per minute
        DataStream<Long> result = validPageCreate
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy.<Long>forBoundedOutOfOrderness(
                                java.time.Duration.ofSeconds(5))
                                .withTimestampAssigner((timestamp, ts) -> timestamp))
                .map(timestamp -> 1L)  // convert each event to 1
                .windowAll(TumblingEventTimeWindows.of(Time.minutes(1)))
                .sum(0);

        // Print results to stdout
        result.map(count -> "Page creates in last minute: " + count).print();

        // Execute the Flink job
        env.execute("MediaWiki Page Create Counter Job");
    }
}