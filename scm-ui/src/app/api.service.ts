import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Alert, ChatReply, ColdChainResult, DashboardSummary, DockDoorMonitor, DockDoorReport, DoorRoi, ExceptionWorkflow, InspectionResult, InventoryItem, OrderStatus, Product,
  PurchaseOrder, ReplenishmentRecommendation, Shipment, ShipmentStatus, TemperatureProfile, WorkflowAction,
} from './models';

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  dashboard() { return this.http.get<DashboardSummary>('/api/dashboard'); }
  inventory() { return this.http.get<InventoryItem[]>('/api/inventory'); }
  lowStock() { return this.http.get<InventoryItem[]>('/api/inventory/low-stock'); }
  adjustInventory(sku: string, warehouseCode: string, delta: number, reason: string) {
    return this.http.post<InventoryItem>('/api/inventory/adjust', { sku, warehouseCode, delta, reason });
  }
  products() { return this.http.get<Product[]>('/api/products'); }

  orders() { return this.http.get<PurchaseOrder[]>('/api/orders'); }
  createOrder(sku: string, warehouseCode: string, quantity: number) {
    return this.http.post<PurchaseOrder>('/api/orders', { sku, warehouseCode, quantity });
  }
  setOrderStatus(orderNumber: string, status: OrderStatus) {
    return this.http.patch<PurchaseOrder>(`/api/orders/${orderNumber}/status`, { status });
  }

  shipments() { return this.http.get<Shipment[]>('/api/shipments'); }
  delayedShipments() { return this.http.get<Shipment[]>('/api/shipments/delayed'); }
  setShipmentStatus(trackingNumber: string, status: ShipmentStatus) {
    return this.http.patch<Shipment>(`/api/shipments/${trackingNumber}/status`, { status });
  }

  alerts() { return this.http.get<Alert[]>('/api/alerts'); }

  /** Live alerts pushed by the core service (from Flink via Kafka + Camel, or raised locally). */
  alertStream(): Observable<Alert> {
    return new Observable<Alert>(subscriber => {
      const source = new EventSource('/api/alerts/stream');
      source.addEventListener('alert', e => subscriber.next(JSON.parse((e as MessageEvent).data)));
      return () => source.close();
    });
  }

  chat(sessionId: string, message: string) {
    return this.http.post<ChatReply>('/ai/chat', { session_id: sessionId, message });
  }

  /** Stores a rating of an assistant reply on its LangSmith trace. */
  chatFeedback(runId: string, score: number, comment = '') {
    return this.http.post<{ recorded: boolean }>('/ai/chat/feedback', { run_id: runId, score, comment });
  }

  rlRecommendations() {
    return this.http.get<ReplenishmentRecommendation[]>('/ai/rl/recommendations');
  }

  rlTrain(iterations: number) {
    return this.http.post<unknown>('/ai/rl/train', { iterations });
  }

  exceptionWorkflows() {
    return this.http.get<ExceptionWorkflow[]>('/ai/workflows/exceptions');
  }

  startExceptionWorkflow(alert: Alert) {
    return this.http.post<ExceptionWorkflow>('/ai/workflows/exceptions', { alert });
  }

  decideExceptionWorkflow(threadId: string, approved: boolean, actions: WorkflowAction[] | null, comment: string) {
    return this.http.post<ExceptionWorkflow>(`/ai/workflows/exceptions/${threadId}/decision`, { approved, actions, comment });
  }

  inspect(file: File, trackingNumber: string | null, useLlm: boolean) {
    const form = new FormData();
    form.append('file', file);
    if (trackingNumber) form.append('tracking_number', trackingNumber);
    form.append('use_llm', String(useLlm));
    return this.http.post<InspectionResult>('/ai/vision/inspect', form);
  }

  coldChainProfiles() { return this.http.get<TemperatureProfile[]>('/ai/vision/cold-chain/profiles'); }
  inspectColdChain(file: File, req: ColdChainRequest) {
    const form = new FormData();
    form.append('file', file);
    if (req.profile) form.append('profile', req.profile);
    if (req.trackingNumber) form.append('tracking_number', req.trackingNumber);
    form.append('thermal', String(req.thermal));
    if (req.thermal) {
      form.append('scale_min_c', String(req.scaleMinC));
      form.append('scale_max_c', String(req.scaleMaxC));
      form.append('palette', req.palette);
    }
    form.append('use_llm', String(req.useLlm));
    return this.http.post<ColdChainResult>('/ai/vision/cold-chain', form);
  }

  analyzeDockDoor(video: File, doorId: string, roi: DoorRoi, maxOpenS: number, reference: File | null, raiseAlerts: boolean) {
    const form = new FormData();
    form.append('file', video);
    form.append('door_id', doorId);
    form.append('roi_x', String(roi.x));
    form.append('roi_y', String(roi.y));
    form.append('roi_w', String(roi.w));
    form.append('roi_h', String(roi.h));
    form.append('max_open_s', String(maxOpenS));
    if (reference) form.append('reference', reference);
    form.append('raise_alerts', String(raiseAlerts));
    return this.http.post<DockDoorReport>('/ai/vision/dock-doors/analyze', form);
  }
  dockDoorMonitors() { return this.http.get<DockDoorMonitor[]>('/ai/vision/dock-doors/monitors'); }
  startDockDoorMonitor(req: { door_id: string; source: string; roi: DoorRoi; max_open_s: number }) {
    return this.http.post<DockDoorMonitor>('/ai/vision/dock-doors/monitors', req);
  }
  stopDockDoorMonitor(id: string) { return this.http.delete<DockDoorMonitor>(`/ai/vision/dock-doors/monitors/${id}`); }
}

export interface ColdChainRequest {
  profile: string;
  trackingNumber: string | null;
  thermal: boolean;
  scaleMinC: number;
  scaleMaxC: number;
  palette: string;
  useLlm: boolean;
}

export function errorMessage(err: any): string {
  return err?.error?.detail ?? err?.message ?? 'Request failed';
}
