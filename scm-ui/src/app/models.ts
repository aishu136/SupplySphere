export interface Supplier {
  id: number;
  name: string;
  contactEmail: string;
  country: string;
  rating: number;
  leadTimeDays: number;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  category: string;
  unitPrice: number;
  supplier: Supplier;
}

export interface InventoryItem {
  id: number;
  product: Product;
  warehouseCode: string;
  quantity: number;
  reorderPoint: number;
  reorderQuantity: number;
  updatedAt: string;
  lowStock: boolean;
}

export type OrderStatus = 'CREATED' | 'APPROVED' | 'SHIPPED' | 'RECEIVED' | 'CANCELLED';

export interface PurchaseOrder {
  id: number;
  orderNumber: string;
  supplier: Supplier;
  product: Product;
  warehouseCode: string;
  quantity: number;
  status: OrderStatus;
  createdAt: string;
  expectedDelivery: string;
}

export type ShipmentStatus = 'CREATED' | 'IN_TRANSIT' | 'DELAYED' | 'DELIVERED' | 'DAMAGED';

export interface Shipment {
  id: number;
  trackingNumber: string;
  purchaseOrder: PurchaseOrder | null;
  carrier: string;
  origin: string;
  destination: string;
  status: ShipmentStatus;
  eta: string;
  lastUpdated: string;
  inspectionNotes: string | null;
}

export interface Alert {
  alertId: string;
  type: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  message: string;
  entityId: string | null;
  timestamp: string;
  source: string;
}

/** Aggregated by the API gateway; a figure is null when the service that owns it is unavailable. */
export interface DashboardSummary {
  skus: number | null;
  unitsOnHand: number | null;
  lowStockItems: number | null;
  openOrders: number | null;
  shipmentsInTransit: number | null;
  delayedShipments: number | null;
  recentAlerts: number | null;
  unavailable: string[];
}

export interface ChatReply {
  reply: string;
  tools_used: string[];
  run_id: string;   // LangSmith run ID of this turn
  traced: boolean;  // true when LangSmith tracing is on
}

export interface PolicyEvaluation {
  avg_cost: number;
  fill_rate: number;
  avg_on_hand: number;
  orders_per_episode: number;
}

export interface ReplenishmentRecommendation {
  sku: string;
  productName: string;
  warehouseCode: string;
  onHand: number;
  onOrder: number;
  reorderPoint: number;
  reorderQuantity: number;
  estimatedDailyDemand: number;
  learnedPolicy: { reorder_point: number; order_quantity: number };
  rlOrderQuantity: number;
  baselineOrderQuantity: number;
  recommendedQuantity: number;
  policyUsed: 'rl' | 'baseline';
  rl: PolicyEvaluation;
  baseline: PolicyEvaluation;
  costSavingPct: number;
}

export interface WorkflowAction {
  type: 'create_purchase_order' | 'update_shipment_status';
  sku?: string | null;
  warehouse_code?: string | null;
  quantity?: number | null;
  tracking_number?: string | null;
  status?: string | null;
  reason: string;
}

export type WorkflowStatus = 'awaiting_approval' | 'completed' | 'partially_failed' | 'rejected' | 'no_action' | 'running';

/** A LangGraph exception-resolution run (see scm-ai-service/app/workflows). */
export interface ExceptionWorkflow {
  threadId: string;
  status: WorkflowStatus;
  alert: Alert;
  kind: 'low_stock' | 'shipment_delay' | 'unsupported';
  context: Record<string, unknown> | null;
  plan: { summary: string; rationale: string; actions: WorkflowAction[] } | null;
  planner: 'claude' | 'rules' | null;
  decision: { approved: boolean; actions: WorkflowAction[]; comment: string } | null;
  results: { action: WorkflowAction; ok: boolean; detail: string }[];
  outcome: string | null;
  updatedAt: string | null;
}

export interface Detection {
  label: string;
  confidence: number;
  box: number[];
}

export interface InspectionResult {
  damaged: boolean;
  summary: string;
  item_counts: Record<string, number>;
  detections: Detection[];
  codes: string[];
  llm_assessment: { damaged: boolean; severity: string; findings: string[]; recommended_action: string } | null;
  annotated_image: string | null;
}

export interface TemperatureProfile {
  name: 'frozen' | 'chilled' | 'cool';
  min_c: number;
  max_c: number;
  description: string;
}

export interface ThermalSpot {
  kind: 'hot' | 'cold';
  peak_c: number;
  area_pct: number;
  box: number[];
}

export interface ColdChainResult {
  breach: boolean;
  severity: 'none' | 'minor' | 'major' | 'critical';
  profile: TemperatureProfile;
  summary: string;
  thermal: {
    min_c: number; max_c: number; mean_c: number; p95_c: number;
    above_range_pct: number; below_range_pct: number; spots: ThermalSpot[];
  } | null;
  detections: Detection[];
  codes: string[];
  llm_assessment: {
    displayed_temperatures_c: number[];
    indicator_status: 'none_visible' | 'ok' | 'triggered' | 'unclear';
    frost_or_ice: boolean; condensation: boolean; spoilage_signs: boolean;
    dates_read: string[]; findings: string[]; recommended_action: string;
  } | null;
  annotated_image: string | null;
}
