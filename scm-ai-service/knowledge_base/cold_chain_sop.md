# Cold Chain Receiving Procedure (SOP-RCV-014)

## Temperature profiles
- Frozen (meat, fish, vegetables, ice cream): -30 to -18 °C.
- Chilled (fresh meat, dairy, ready meals): 0 to 4 °C.
- Cool (chill-sensitive produce such as bananas and tomatoes, chocolate): 8 to 15 °C.

## Inspection at the dock
1. Before the reefer doors open, record the reefer unit display and download the data logger.
2. Take a thermal image of the load as soon as the doors open, and of each pallet on the dock. Pallets may
   not wait on an unrefrigerated dock for more than 20 minutes.
3. Photograph the cartons and any time-temperature indicator labels. The vision inspection looks for frost
   or ice crystals inside packaging (a sign of thawing and refreezing), condensation, leaks and spoilage,
   and reads displays, indicators and use-by dates.

## Reefer dock doors
1. A reefer dock door may stay open for at most 2 minutes at a time, and only while a trailer is sealed
   against the dock shelter. Close it between pallet runs.
2. Every reefer door has a camera. Openings over the limit raise a DOCK_DOOR_OPEN alert to the shift lead.
3. Three or more over-limit openings at one door in a shift: check the dock shelter seal and the trailer's
   reefer log, and probe the core temperature of the last pallets received through that door.

## When a breach is detected
1. The shipment is marked DAMAGED and a COLD_CHAIN_BREACH alert is raised. Do not receive the goods into
   available stock.
2. Move the pallets to the temperature-controlled quarantine area (cold room CQ1 in WH-EAST, CQ2 in WH-WEST).
3. Probe the core temperature of three cartons per pallet: top, middle and bottom.
4. Notify the carrier within 12 hours with the thermal images and logger download; cold-chain claims filed
   later are usually rejected.

## Severity
- Minor: one sign of abuse (e.g. light condensation) or a reading up to 2 °C out of range. Receive after
  core probing confirms the product is in range.
- Major: a reading 2 to 5 °C out of range, a hot spot covering more than 2% of the load, or several signs of
  abuse. Quality assurance decides per pallet.
- Critical: more than 5 °C out of range, a triggered temperature indicator on frozen goods, or a hot spot
  covering more than 10% of the load. Reject the delivery.

## Chilled goods below range
Chilled goods that have frozen, and cool-profile produce held below 8 °C, suffer freezing or chilling
injury. Treat cold spots the same as hot spots.
