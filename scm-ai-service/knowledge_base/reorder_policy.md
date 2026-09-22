# Inventory Reorder Policy (SOP-INV-004)

## Reorder trigger
A purchase order must be raised when on-hand quantity for a SKU at a warehouse falls to or below its
reorder point. The reorder point is set per SKU and warehouse as:

    reorder point = average daily demand x supplier lead time (days) + safety stock

Safety stock is 7 days of average demand for A-class items (Electronics) and 4 days for B/C-class items
(Hardware, Packaging).

## Order quantity
Order the configured reorder quantity for the SKU and warehouse. Planners may increase it by up to 50%
when a DEMAND_SPIKE alert has been raised for the SKU in the last 24 hours. Increases above 50% need
approval from the supply planning manager.

## Supplier selection
Order from the SKU's contracted supplier. If that supplier's rating is below 4.0, or the shipment for its
last order was delayed by more than 48 hours, notify procurement so they can consider a secondary source.

## Stock-outs
A stock-out (zero units) at any warehouse is a CRITICAL event. Before ordering, check whether another
warehouse holds more than twice its reorder point; if it does, raise an inter-warehouse transfer first,
as it is faster than a supplier order.
