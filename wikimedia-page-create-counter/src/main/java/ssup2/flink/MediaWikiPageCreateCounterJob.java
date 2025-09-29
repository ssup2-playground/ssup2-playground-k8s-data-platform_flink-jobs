package ssup2.flink;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Properties;

public class MediaWikiPageCreateCounterJob {
    
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public static void main(String[] args) throws Exception {
        // Set S3 configuration for Flink checkpoint storage
        Configuration conf = new Configuration();
        conf.setString("s3.access-key", System.getenv().getOrDefault("AWS_ACCESS_KEY_ID", "root"));
        conf.setString("s3.secret-key", System.getenv().getOrDefault("AWS_SECRET_ACCESS_KEY", "root123!"));
        conf.setString("s3.endpoint", System.getenv().getOrDefault("AWS_ENDPOINT", "minio.minio:9000"));
        conf.setBoolean("s3.path.style.access", true);

        // Set Flink execution environment
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        env.enableCheckpointing(10000, CheckpointingMode.EXACTLY_ONCE);
        env.getCheckpointConfig().setMinPauseBetweenCheckpoints(5000);
        env.getCheckpointConfig().setCheckpointTimeout(6000);
        env.getCheckpointConfig().setMaxConcurrentCheckpoints(1);
        env.getCheckpointConfig().enableUnalignedCheckpoints(true);
        env.getCheckpointConfig().setExternalizedCheckpointCleanup(
            CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        env.getCheckpointConfig().setCheckpointStorage("s3://flink/checkpoints/page-create-counter");
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3, 5000));

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

        // Parse JSON and filter
        DataStream<Long> validPageCreate = kafkaStream
            .map(json -> {
                try {
                    JsonNode root = mapper.readTree(json);
                    String timestamp = root.path("meta").path("dt").asText();
                    int namespace = root.path("page_namespace").asInt();
                    boolean isRedirect = root.path("page_is_redirect").asBoolean();
                    
                    // main namespace(0) and not redirect
                    if (namespace == 0 && !isRedirect) {
                        return Instant.parse(timestamp).toEpochMilli();
                    }
                    return null;
                } catch (Exception e) {
                    System.err.println("Failed to parse JSON: " + e.getMessage());
                    return null;
                }
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