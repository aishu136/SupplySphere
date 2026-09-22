# Shipment Delay Escalation (SOP-LOG-007)

## Detection
The streaming platform raises a SHIPMENT_DELAYED alert when a shipment passes its ETA without being
delivered, or when a carrier reports a delay.

## Escalation steps
1. Within 4 hours of the alert, the logistics coordinator contacts the carrier for a revised ETA and
   updates the shipment record.
2. If the revised ETA is more than 48 hours late and the SKU is at or below its reorder point at the
   destination warehouse, the planner evaluates an expedited order or an inter-warehouse transfer.
3. Delays longer than 5 days are escalated to the supplier account manager and logged for the quarterly
   supplier scorecard.

## Customer impact
When a delay threatens a customer commitment, customer service must be told within 1 business day,
with the revised date and any mitigation in place.
