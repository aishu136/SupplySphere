import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../api.service';
import { Alert, DashboardSummary, InventoryItem, Shipment } from '../models';

@Component({
  selector: 'app-dashboard',
  imports: [DatePipe, DecimalPipe],
  template: `
    <h1>Dashboard</h1>

    @if (summary(); as s) {
      @if (s.unavailable.length) {
        <div class="card error">Some figures are unavailable because these services are not responding:
          {{ s.unavailable.join(', ') }}.</div>
      }
      <div class="kpis">
        <div class="kpi"><div class="label">SKUs</div><div class="value">{{ s.skus ?? '—' }}</div></div>
        <div class="kpi"><div class="label">Units on hand</div><div class="value">{{ s.unitsOnHand === null ? '—' : (s.unitsOnHand | number) }}</div></div>
        <div class="kpi" [class.bad]="(s.lowStockItems ?? 0) > 0"><div class="label">Low-stock positions</div><div class="value">{{ s.lowStockItems ?? '—' }}</div></div>
        <div class="kpi"><div class="label">Open POs</div><div class="value">{{ s.openOrders ?? '—' }}</div></div>
        <div class="kpi"><div class="label">In transit</div><div class="value">{{ s.shipmentsInTransit ?? '—' }}</div></div>
        <div class="kpi" [class.bad]="(s.delayedShipments ?? 0) > 0"><div class="label">Delayed shipments</div><div class="value">{{ s.delayedShipments ?? '—' }}</div></div>
      </div>
    }

    <div class="grid two">
      <section class="card">
        <h2>Live alerts <span class="muted">({{ alerts().length }})</span></h2>
        @if (alerts().length === 0) { <p class="muted">No alerts yet.</p> }
        <ul class="alerts">
          @for (a of alerts(); track a.alertId) {
            <li>
              <div class="row"><span class="badge" [class]="a.severity">{{ a.severity }}</span><strong>{{ a.type }}</strong>
                <span class="muted">{{ a.timestamp | date: 'MMM d, HH:mm:ss' }} · {{ a.source }}</span></div>
              <div>{{ a.message }}</div>
            </li>
          }
        </ul>
      </section>

      <div>
        <section class="card">
          <h2>Below reorder point</h2>
          <div class="table-wrap"><table>
            <thead><tr><th>SKU</th><th>Warehouse</th><th class="num">On hand</th><th class="num">Reorder pt</th></tr></thead>
            <tbody>
              @for (i of lowStock(); track i.id) {
                <tr><td>{{ i.product.sku }}</td><td>{{ i.warehouseCode }}</td><td class="num">{{ i.quantity }}</td><td class="num">{{ i.reorderPoint }}</td></tr>
              } @empty { <tr><td colspan="4" class="muted">All positions healthy.</td></tr> }
            </tbody>
          </table></div>
        </section>

        <section class="card">
          <h2>Delayed shipments</h2>
          <div class="table-wrap"><table>
            <thead><tr><th>Tracking</th><th>Carrier</th><th>Destination</th><th>ETA</th></tr></thead>
            <tbody>
              @for (s of delayed(); track s.id) {
                <tr><td>{{ s.trackingNumber }}</td><td>{{ s.carrier }}</td><td>{{ s.destination }}</td><td>{{ s.eta | date: 'MMM d, HH:mm' }}</td></tr>
              } @empty { <tr><td colspan="4" class="muted">Nothing late.</td></tr> }
            </tbody>
          </table></div>
        </section>
      </div>
    </div>
  `,
})
export class Dashboard implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);

  readonly summary = signal<DashboardSummary | null>(null);
  readonly alerts = signal<Alert[]>([]);
  readonly lowStock = signal<InventoryItem[]>([]);
  readonly delayed = signal<Shipment[]>([]);

  ngOnInit() {
    this.refresh();
    this.api.alerts().subscribe(a => this.alerts.set(a));
    this.api.alertStream().pipe(takeUntilDestroyed(this.destroyRef)).subscribe(alert => {
      this.alerts.update(list => [alert, ...list].slice(0, 200));
      this.refresh();
    });
  }

  private refresh() {
    this.api.dashboard().subscribe(s => this.summary.set(s));
    this.api.lowStock().subscribe(i => this.lowStock.set(i));
    this.api.delayedShipments().subscribe(s => this.delayed.set(s));
  }
}
