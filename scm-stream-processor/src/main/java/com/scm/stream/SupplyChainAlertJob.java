package com.scm.stream;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * Reads the scm.* domain event topics and writes derived alerts to scm.alerts,
 * which alert-service consumes and pushes to the Angular UI.
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

        DataStream<String> json = env.fromSource(source, WatermarkStrategy.noWatermarks(), "scm-domain-events");

        KafkaSink<String> sink = KafkaSink.<String>builder()
                .setBootstrapServers(brokers)
                .setRecordSerializer(KafkaRecordSerializationSchema.builder()
                        .setTopic("scm.alerts")
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        alerts(json,
                Duration.ofMinutes(intEnv("SHIPMENT_GRACE_MINUTES", 0)),
                Duration.ofMinutes(intEnv("DEMAND_WINDOW_MINUTES", 10)),
                intEnv("DEMAND_MAX_ORDERS", 3),
                intEnv("DEMAND_MAX_UNITS", 10_000))
                .sinkTo(sink)
                .name("scm-alerts-sink");

        env.execute("scm-supply-chain-alerts");
    }

    /** The processing pipeline between source and sink: domain-event JSON in, alert JSON out. */
    @SuppressWarnings("deprecation")
    static DataStream<String> alerts(DataStream<String> json, Duration shipmentGrace, Duration demandWindow,
                                     int demandMaxOrders, int demandMaxUnits) {
        // Every type must have a native Flink serializer; a Kryo fallback fails at job build time.
        json.getExecutionEnvironment().getConfig().disableGenericTypes();

        DataStream<ScmEvent> events = json
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
                .process(new ShipmentDelayDetector(shipmentGrace))
                .name("shipment-delay-detector");

        DataStream<AlertEvent> demand = events
                .filter(e -> "ORDER_CREATED".equals(e.type))
                .filter(e -> e.sku != null && e.quantity != null)
                .keyBy(e -> e.sku)
                .window(TumblingProcessingTimeWindows.of(demandWindow))
                .process(new DemandSpikeDetector(demandMaxOrders, demandMaxUnits))
                .name("demand-spike-detector");

        return lowStock.union(delays, demand)
                .map(a -> new ObjectMapper().writeValueAsString(a))
                .name("alert-to-json");
    }

    /**
     * Drops malformed messages instead of failing the job. Only parsing is guarded: errors further
     * down the pipeline must surface, not be mistaken for bad input.
     */
    static class JsonToEvent implements FlatMapFunction<String, ScmEvent> {
        private static final Logger log = LoggerFactory.getLogger(JsonToEvent.class);
        private transient ObjectMapper mapper;

        @Override
        public void flatMap(String json, Collector<ScmEvent> out) {
            if (mapper == null) {
                mapper = new ObjectMapper();
            }
            ScmEvent event;
            try {
                event = parse(mapper.readTree(json));
            } catch (Exception e) {
                log.warn("Skipping malformed event: {}", e.getMessage());
                return;
            }
            if (event.type != null && event.entityId != null) {
                out.collect(event);
            }
        }

        static ScmEvent parse(JsonNode node) {
            ScmEvent e = new ScmEvent();
            e.eventId = text(node, "eventId");
            e.type = text(node, "type");
            e.source = text(node, "source");
            e.entityId = text(node, "entityId");
            e.timestamp = text(node, "timestamp");
            JsonNode data = node.path("data");
            e.sku = text(data, "sku");
            e.warehouseCode = text(data, "warehouseCode");
            e.quantity = integer(data, "quantity");
            e.reorderPoint = integer(data, "reorderPoint");
            e.status = text(data, "status");
            e.eta = text(data, "eta");
            e.carrier = text(data, "carrier");
            e.destination = text(data, "destination");
            return e;
        }

        private static String text(JsonNode node, String field) {
            JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asText();
        }

        private static Integer integer(JsonNode node, String field) {
            JsonNode v = node.get(field);
            return v == null || v.isNull() ? null : v.asInt();
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
