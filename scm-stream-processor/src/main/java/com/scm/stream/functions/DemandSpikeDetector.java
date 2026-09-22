package com.scm.stream.functions;

import com.scm.stream.model.AlertEvent;
import com.scm.stream.model.ScmEvent;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;

/** Per-SKU window over ORDER_CREATED events; alerts when order count or volume exceeds thresholds. */
public class DemandSpikeDetector extends ProcessWindowFunction<ScmEvent, AlertEvent, String, TimeWindow> {

    private final int maxOrders;
    private final int maxUnits;

    public DemandSpikeDetector(int maxOrders, int maxUnits) {
        this.maxOrders = maxOrders;
        this.maxUnits = maxUnits;
    }

    @Override
    public void process(String sku, Context ctx, Iterable<ScmEvent> orders, Collector<AlertEvent> out) {
        int count = 0;
        int units = 0;
        for (ScmEvent e : orders) {
            count++;
            units += e.integer("quantity");
        }
        if (count >= maxOrders || units >= maxUnits) {
            long minutes = (ctx.window().getEnd() - ctx.window().getStart()) / 60_000;
            out.collect(AlertEvent.of("DEMAND_SPIKE", "MEDIUM",
                    "%d purchase orders totalling %d units for %s in the last %d min".formatted(count, units, sku, minutes),
                    sku));
        }
    }
}
