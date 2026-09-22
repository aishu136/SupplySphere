import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, errorMessage } from '../api.service';
import { Shipment, ShipmentStatus } from '../models';

@Component({
  selector: 'app-shipments',
  imports: [DatePipe, RouterLink],
  template: `
    <h1>Shipments</h1>
    @if (error()) { <div class="error">{{ error() }}</div> }
    <section class="card">
      <div class="table-wrap"><table>
        <thead><tr><th>Tracking</th><th>PO</th><th>Carrier</th><th>Route</th><th>ETA</th><th>Status</th>
          <th>Inspection</th><th>Update</th><th></th></tr></thead>
        <tbody>
          @for (s of shipments(); track s.id) {
            <tr>
              <td>{{ s.trackingNumber }}</td>
              <td>{{ s.purchaseOrder?.orderNumber ?? '—' }}</td>
              <td>{{ s.carrier }}</td>
              <td>{{ s.origin }} → {{ s.destination }}</td>
              <td [class.error]="isLate(s)">{{ s.eta | date: 'MMM d, HH:mm' }}</td>
              <td><span class="badge" [class]="s.status">{{ s.status }}</span></td>
              <td class="muted" style="white-space: normal; max-width: 260px">{{ s.inspectionNotes ?? '' }}</td>
              <td>
                <select #st [value]="s.status" (change)="setStatus(s, $any(st.value))">
                  @for (option of statuses; track option) { <option [value]="option">{{ option }}</option> }
                </select>
              </td>
              <td><a [routerLink]="['/vision']" [queryParams]="{ tracking: s.trackingNumber }">Inspect</a></td>
            </tr>
          }
        </tbody>
      </table></div>
    </section>
  `,
})
export class Shipments implements OnInit {
  private readonly api = inject(ApiService);

  readonly shipments = signal<Shipment[]>([]);
  readonly error = signal('');
  readonly statuses: ShipmentStatus[] = ['CREATED', 'IN_TRANSIT', 'DELAYED', 'DELIVERED', 'DAMAGED'];

  ngOnInit() { this.load(); }

  load() { this.api.shipments().subscribe(s => this.shipments.set(s)); }

  isLate(s: Shipment) {
    return (s.status === 'CREATED' || s.status === 'IN_TRANSIT') && new Date(s.eta) < new Date();
  }

  setStatus(s: Shipment, status: ShipmentStatus) {
    if (status === s.status) return;
    this.error.set('');
    this.api.setShipmentStatus(s.trackingNumber, status).subscribe({
      next: () => this.load(),
      error: e => { this.error.set(errorMessage(e)); this.load(); },
    });
  }
}
