package com.scm.stream.functions;

import com.scm.stream.model.AlertEvent;
import com.scm.stream.model.ScmEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Keyed by tracking number. Arms a processing-time timer at ETA + grace period whenever a shipment
 * is open; if no DELIVERED/DAMAGED event arrives before it fires, emits SHIPMENT_DELAYED.
 * A carrier-reported DELAYED status alerts immediately.
 */
public class ShipmentDelayDetector extends KeyedProcessFunction<String, ScmEvent, AlertEvent> {

    private static final Set<String> OPEN = Set.of("CREATED", "IN_TRANSIT");
    private static final Set<String> CLOSED = Set.of("DELIVERED", "DAMAGED");

    private final long graceMillis;

    private transient ValueState<Long> timer;
    private transient ValueState<String> destination;

    public ShipmentDelayDetector(Duration grace) {
        this.graceMillis = grace.toMillis();
    }

    @Override
    public void open(OpenContext openContext) {
        timer = getRuntimeContext().getState(new ValueStateDescriptor<>("delay-timer", Long.class));
        destination = getRuntimeContext().getState(new ValueStateDescriptor<>("destination", String.class));
    }

    @Override
    public void processElement(ScmEvent event, Context ctx, Collector<AlertEvent> out) throws Exception {
        String status = event.status;
        String eta = event.eta;
        destination.update(event.destination);
        cancelTimer(ctx);

        if ("DELAYED".equals(status)) {
            out.collect(AlertEvent.of("SHIPMENT_DELAYED", "HIGH",
                    "Carrier %s reported shipment %s to %s as delayed".formatted(
                            event.carrier, ctx.getCurrentKey(), event.destination),
                    ctx.getCurrentKey()));
        } else if (OPEN.contains(status) && eta != null) {
            long now = ctx.timerService().currentProcessingTime();
            long fireAt = Math.max(Instant.parse(eta).toEpochMilli() + graceMillis, now + 1000);
            ctx.timerService().registerProcessingTimeTimer(fireAt);
            timer.update(fireAt);
        } else if (CLOSED.contains(status)) {
            destination.clear();
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<AlertEvent> out) throws Exception {
        Long armed = timer.value();
        if (armed == null || armed != timestamp) {
            return; // stale timer from an earlier ETA
        }
        timer.clear();
        out.collect(AlertEvent.of("SHIPMENT_DELAYED", "HIGH",
                "Shipment %s to %s passed its ETA without delivery".formatted(ctx.getCurrentKey(), destination.value()),
                ctx.getCurrentKey()));
    }

    private void cancelTimer(Context ctx) throws Exception {
        Long armed = timer.value();
        if (armed != null) {
            ctx.timerService().deleteProcessingTimeTimer(armed);
            timer.clear();
        }
    }
}
