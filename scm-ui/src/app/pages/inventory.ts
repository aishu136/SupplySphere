import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { InventoryItem } from '../models';

@Component({
  selector: 'app-inventory',
  imports: [FormsModule],
  template: `
    <h1>Inventory</h1>
    <section class="card">
      <div class="row mb-3">
        <input placeholder="Filter by SKU, name or warehouse" [ngModel]="filter()" (ngModelChange)="filter.set($event)" class="min-w-[280px]">
        <label class="row"><input type="checkbox" [ngModel]="lowOnly()" (ngModelChange)="lowOnly.set($event)"> Low stock only</label>
      </div>
      @if (error()) { <div class="error">{{ error() }}</div> }
      @if (notice()) { <div class="muted mb-2">{{ notice() }}</div> }
      <div class="table-wrap"><table>
        <thead><tr>
          <th>SKU</th><th>Product</th><th>Supplier</th><th>Warehouse</th>
          <th class="num">On hand</th><th class="num">Reorder pt</th><th>Adjust</th><th></th>
        </tr></thead>
        <tbody>
          @for (i of visible(); track i.id) {
            <tr [class.low]="i.lowStock">
              <td>{{ i.product.sku }}</td>
              <td>{{ i.product.name }}</td>
              <td>{{ i.product.supplier.name }}</td>
              <td>{{ i.warehouseCode }}</td>
              <td class="num">{{ i.quantity }}</td>
              <td class="num">{{ i.reorderPoint }}</td>
              <td>
                <div class="row">
                  <input type="number" #delta value="0" class="w-20">
                  <button class="secondary" (click)="adjust(i, +delta.value)">Apply</button>
                </div>
              </td>
              <td>
                @if (i.lowStock) {
                  <button (click)="reorder(i)">Reorder {{ i.reorderQuantity }}</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table></div>
    </section>
  `,
})
export class Inventory implements OnInit {
  private readonly api = inject(ApiService);

  readonly items = signal<InventoryItem[]>([]);
  readonly filter = signal('');
  readonly lowOnly = signal(false);
  readonly error = signal('');
  readonly notice = signal('');

  readonly visible = computed(() => {
    const f = this.filter().toLowerCase();
    return this.items().filter(i =>
      (!this.lowOnly() || i.lowStock) &&
      (!f || `${i.product.sku} ${i.product.name} ${i.warehouseCode}`.toLowerCase().includes(f)));
  });

  ngOnInit() { this.load(); }

  load() {
    this.api.inventory().subscribe({ next: i => this.items.set(i), error: e => this.error.set(errorMessage(e)) });
  }

  adjust(item: InventoryItem, delta: number) {
    if (!delta) return;
    this.error.set('');
    this.api.adjustInventory(item.product.sku, item.warehouseCode, delta, 'manual adjustment').subscribe({
      next: () => this.load(),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  reorder(item: InventoryItem) {
    this.error.set('');
    this.api.createOrder(item.product.sku, item.warehouseCode, item.reorderQuantity).subscribe({
      next: po => this.notice.set(`Created ${po.orderNumber} with ${po.supplier.name}, expected ${po.expectedDelivery}.`),
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
