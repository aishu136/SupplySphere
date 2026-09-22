package com.scm.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scm.stream.functions.DemandSpikeDetector;
import com.scm.stream.functions.LowStockDetector;
import com.scm.stream.functions.ShipmentDelayDetector;
import com.scm.stream.model.AlertEvent;
import com.scm.stream.model.ScmEvent;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.util.Collector;

import java.time.Duration;

/**
 * Reads the scm.* domain event topics and writes derived alerts to scm.alerts,
 * which the Spring Boot service consumes via Camel and pushes to the Angular UI.
 *
 * Configuration (env vars): KAFKA_BOOTSTRAP_SERVERS, SHIPMENT_GRACE_MINUTES,
 * DEMAND_WINDOW_MINUTES, DEMAND_MAX_ORDERS, DEMAND_MAX_UNITS.
 */
public class SupplyChainAlertJob {

    public static void main(String[] args) throws Exception {
        String brokers = env("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.enableCheckpointing(30_000);

        KafkaSource<String> source = KafkaSource.<String>builder()
                .setBootstrapServers(brokers)
                .setTopics("scm.inventory.events", "scm.order.events", "scm.shipment.events")
                .setGroupId("scm-stream-processor")
                .setStartingOffsets(OffsetsInitializer.latest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<ScmEvent> events = env
                .fromSource(source, WatermarkStrategy.noWatermarks(), "scm-domain-events")
                .flatMap(new JsonToEvent())
                .name("parse-events");

        DataStream<AlertEvent> lowStock = events
                .filter(e -> "INVENTORY_UPDATED".equals(e.type))
                .keyBy(e -> e.entityId)
                .process(new LowStockDetector())
                .name("low-stock-detector");

        DataStream<AlertEvent> delays = events
                .filter(e -> e.type != null && e.type.startsWith("SHIPMENT_"))
                .keyBy(e -> e.entityId)
                .process(new ShipmentDelayDetector(Duration.ofMinutes(intEnv("SHIPMENT_GRACE_MINUTES", 0))))
                .name("shipment-delay-detector");

        DataStream<AlertEvent> demand = events
                .filter(e -> "ORDER_CREATED".equals(e.type))
                .keyBy(e -> e.str("sku"))
                .window(TumblingProcessingTimeWindows.of(Duration.ofMinutes(intEnv("DEMAND_WINDOW_MINUTES", 10))))
                .process(new DemandSpikeDetector(intEnv("DEMAND_MAX_ORDERS", 3), intEnv("DEMAND_MAX_UNITS", 10_000)))
                .name("demand-spike-detector");

        KafkaSink<String> sink = KafkaSink.<String>builder()
                .setBootstrapServers(brokers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic("scm.alerts")
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        lowStock.union(delays, demand)
                .map(a -> new ObjectMapper().writeValueAsString(a))
                .name("alert-to-json")
                .sinkTo(sink)
                .name("scm-alerts-sink");

        env.execute("scm-supply-chain-alerts");
    }

    /** Drops malformed messages instead of failing the job. */
    static class JsonToEvent implements FlatMapFunction<String, ScmEvent> {
        private transient ObjectMapper mapper;

        @Override
        public void flatMap(String json, Collector<ScmEvent> out) {
            if (mapper == null) {
                mapper = new ObjectMapper();
            }
            try {
                ScmEvent e = mapper.readValue(json, ScmEvent.class);
                if (e.type != null && e.entityId != null) {
                    out.collect(e);
                }
            } catch (Exception ignored) {
                // poison message: skip
            }
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static int intEnv(String key, int fallback) {
        return Integer.parseInt(env(key, String.valueOf(fallback)));
    }
}
