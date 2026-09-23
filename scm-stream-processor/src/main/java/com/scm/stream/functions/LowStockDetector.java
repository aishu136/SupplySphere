package com.scm.stream.functions;

import com.scm.stream.model.AlertEvent;
import com.scm.stream.model.ScmEvent;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * Keyed by sku@warehouse. Emits one LOW_STOCK alert when stock crosses below the reorder point,
 * and stays quiet until stock recovers above it (edge-triggered, no alert storms).
 */
public class LowStockDetector extends KeyedProcessFunction<String, ScmEvent, AlertEvent> {

    private transient ValueState<Boolean> alerted;

    @Override
    public void open(OpenContext openContext) {
        alerted = getRuntimeContext().getState(new ValueStateDescriptor<>("low-stock-alerted", Boolean.class));
    }

    @Override
    public void processElement(ScmEvent event, Context ctx, Collector<AlertEvent> out) throws Exception {
        int quantity = event.quantity();
        int reorderPoint = event.reorderPoint();
        boolean low = quantity <= reorderPoint;
        boolean alreadyAlerted = Boolean.TRUE.equals(alerted.value());

        if (low && !alreadyAlerted) {
            String severity = quantity == 0 ? "CRITICAL" : "HIGH";
            out.collect(AlertEvent.of("LOW_STOCK", severity,
                    "%s at %s is at %d units (reorder point %d)".formatted(
                            event.sku, event.warehouseCode, quantity, reorderPoint),
                    event.sku));
            alerted.update(true);
        } else if (!low && alreadyAlerted) {
            alerted.clear();
        }
    }
}
