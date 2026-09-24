import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Alert, ChatReply, DashboardSummary, ExceptionWorkflow, InspectionResult, InventoryItem, OrderStatus, Product,
  PurchaseOrder, ReplenishmentRecommendation, Shipment, ShipmentStatus, WorkflowAction,
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
}

export function errorMessage(err: any): string {
  return err?.error?.detail ?? err?.message ?? 'Request failed';
}
