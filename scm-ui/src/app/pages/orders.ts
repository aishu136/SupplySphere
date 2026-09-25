import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { OrderStatus, Product, PurchaseOrder } from '../models';

@Component({
  selector: 'app-orders',
  imports: [FormsModule, DatePipe],
  template: `
    <h1>Purchase orders</h1>

    <section class="card">
      <h2>New purchase order</h2>
      <form class="row" (ngSubmit)="create()">
        <select name="sku" [(ngModel)]="sku" required>
          <option value="" disabled>Product</option>
          @for (p of products(); track p.id) { <option [value]="p.sku">{{ p.sku }} · {{ p.name }}</option> }
        </select>
        <select name="warehouse" [(ngModel)]="warehouse">
          <option>WH-EAST</option><option>WH-WEST</option>
        </select>
        <input name="qty" type="number" min="1" [(ngModel)]="quantity" class="w-[100px]">
        <button type="submit" [disabled]="!sku || quantity < 1">Create</button>
      </form>
      @if (error()) { <div class="error">{{ error() }}</div> }
    </section>

    <section class="card">
      <div class="table-wrap"><table>
        <thead><tr><th>Order</th><th>SKU</th><th>Supplier</th><th>Warehouse</th><th class="num">Qty</th>
          <th>Status</th><th>Expected</th><th>Created</th><th></th></tr></thead>
        <tbody>
          @for (o of orders(); track o.id) {
            <tr>
              <td>{{ o.orderNumber }}</td><td>{{ o.product.sku }}</td><td>{{ o.supplier.name }}</td>
              <td>{{ o.warehouseCode }}</td><td class="num">{{ o.quantity }}</td>
              <td><span class="badge" [class]="o.status">{{ o.status }}</span></td>
              <td>{{ o.expectedDelivery }}</td><td>{{ o.createdAt | date: 'MMM d, HH:mm' }}</td>
              <td class="row">
                @if (o.status === 'CREATED') {
                  <button class="secondary" (click)="setStatus(o, 'APPROVED')">Approve</button>
                  <button class="secondary" (click)="setStatus(o, 'CANCELLED')">Cancel</button>
                }
                @if (o.status === 'SHIPPED') {
                  <button class="secondary" (click)="setStatus(o, 'RECEIVED')">Receive</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table></div>
    </section>
  `,
})
export class Orders implements OnInit {
  private readonly api = inject(ApiService);

  readonly orders = signal<PurchaseOrder[]>([]);
  readonly products = signal<Product[]>([]);
  readonly error = signal('');
  sku = '';
  warehouse = 'WH-EAST';
  quantity = 100;

  ngOnInit() {
    this.load();
    this.api.products().subscribe(p => this.products.set(p));
  }

  load() { this.api.orders().subscribe(o => this.orders.set(o)); }

  create() {
    this.error.set('');
    this.api.createOrder(this.sku, this.warehouse, this.quantity).subscribe({
      next: () => this.load(),
      error: e => this.error.set(errorMessage(e)),
    });
  }

  setStatus(order: PurchaseOrder, status: OrderStatus) {
    this.error.set('');
    this.api.setOrderStatus(order.orderNumber, status).subscribe({
      next: () => this.load(),
      error: e => this.error.set(errorMessage(e)),
    });
  }
}
