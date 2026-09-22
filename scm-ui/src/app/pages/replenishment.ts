import { DecimalPipe, PercentPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { ReplenishmentRecommendation } from '../models';

@Component({
  selector: 'app-replenishment',
  imports: [FormsModule, DecimalPipe, PercentPipe],
  template: `
    <h1>RL replenishment</h1>
    <p class="muted">One reinforcement-learning agent per SKU × warehouse learns its own reorder point and order quantity
      by policy search (cross-entropy method) in a simulator built from supplier lead times, reorder quantities and prices.
      Each learned policy is benchmarked against the SOP rule on held-out simulated demand, and is used only where it was cheaper.</p>

    <section class="card">
      <form class="row" (ngSubmit)="train()">
        <label class="row">Training iterations <input name="iterations" type="number" min="5" max="200" [(ngModel)]="iterations" style="width: 90px"></label>
        <button type="submit" [disabled]="busy()">{{ busy() ? 'Working…' : 'Retrain agents' }}</button>
        <button type="button" class="secondary" (click)="load()" [disabled]="busy()">Refresh</button>
      </form>
      @if (error()) { <div class="error">{{ error() }}</div> }
      @if (notice()) { <div class="muted" style="margin-top: 8px">{{ notice() }}</div> }
    </section>

    <section class="card">
      <div class="table-wrap"><table>
        <thead><tr>
          <th>SKU</th><th>Warehouse</th><th class="num">On hand</th><th class="num">On order</th><th class="num">Demand/day</th>
          <th class="num">SOP s / Q</th><th class="num">Learned s / Q</th>
          <th class="num">RL qty</th><th class="num">SOP qty</th><th>Policy</th>
          <th class="num">Cost RL / SOP</th><th class="num">Fill RL / SOP</th><th class="num">Saving</th><th></th>
        </tr></thead>
        <tbody>
          @for (r of recs(); track r.sku + r.warehouseCode) {
            <tr [class.low]="r.recommendedQuantity > 0">
              <td>{{ r.sku }}</td><td>{{ r.warehouseCode }}</td>
              <td class="num">{{ r.onHand }}</td><td class="num">{{ r.onOrder }}</td>
              <td class="num">{{ r.estimatedDailyDemand | number: '1.1-1' }}</td>
              <td class="num">{{ r.reorderPoint }} / {{ r.reorderQuantity }}</td>
              <td class="num">{{ r.learnedPolicy.reorder_point | number: '1.0-0' }} / {{ r.learnedPolicy.order_quantity | number: '1.0-0' }}</td>
              <td class="num">{{ r.rlOrderQuantity }}</td><td class="num">{{ r.baselineOrderQuantity }}</td>
              <td><span class="badge" [class]="r.policyUsed === 'rl' ? 'ok' : 'MEDIUM'">{{ r.policyUsed === 'rl' ? 'RL' : 'SOP' }}</span></td>
              <td class="num">{{ r.rl.avg_cost | number: '1.0-0' }} / {{ r.baseline.avg_cost | number: '1.0-0' }}</td>
              <td class="num">{{ r.rl.fill_rate | percent: '1.1-1' }} / {{ r.baseline.fill_rate | percent: '1.1-1' }}</td>
              <td class="num">{{ r.costSavingPct }}%</td>
              <td>
                @if (r.recommendedQuantity > 0) {
                  <button (click)="order(r)">Order {{ r.recommendedQuantity }}</button>
                }
              </td>
            </tr>
          } @empty {
            <tr><td colspan="14" class="muted">{{ busy() ? 'Training agents on first use…' : 'No recommendations.' }}</td></tr>
          }
        </tbody>
      </table></div>
      <p class="muted">s = reorder point (order when stock on hand + on order falls to it), Q = order quantity.
        Costs are simulated 120-day totals (holding, lost sales, ordering), averaged over 50 held-out demand scenarios.</p>
    </section>
  `,
})
export class Replenishment implements OnInit {
  private readonly api = inject(ApiService);

  readonly recs = signal<ReplenishmentRecommendation[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly notice = signal('');
  iterations = 20;

  ngOnInit() { this.load(); }

  load() {
    this.busy.set(true);
    this.error.set('');
    this.api.rlRecommendations().subscribe({
      next: r => { this.recs.set(r); this.busy.set(false); },
      error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }

  train() {
    this.busy.set(true);
    this.error.set('');
    this.api.rlTrain(this.iterations).subscribe({
      next: () => { this.notice.set(`Retrained all agents for ${this.iterations} iterations.`); this.load(); },
      error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }

  order(r: ReplenishmentRecommendation) {
    this.error.set('');
    this.api.createOrder(r.sku, r.warehouseCode, r.recommendedQuantity).subscribe({
      next: po => { this.notice.set(`Created ${po.orderNumber} for ${r.recommendedQuantity} × ${r.sku}.`); this.load(); },
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
