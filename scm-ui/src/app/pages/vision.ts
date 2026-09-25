import { KeyValuePipe, PercentPipe } from '@angular/common';
import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { InspectionResult, Shipment } from '../models';

@Component({
  selector: 'app-vision',
  imports: [FormsModule, KeyValuePipe, PercentPipe],
  styles: `
    .preview { max-width: 100%; border-radius: 8px; border: 1px solid var(--border); }
    .verdict {
      font-size: 18px; font-weight: 650; margin-bottom: 8px;
      &.bad { color: var(--danger); }
      &.good { color: var(--ok); }
    }
  `,
  template: `
    <h1>Vision inspection</h1>
    <p class="muted">Upload a dock photo of an inbound shipment. Object detection, label decoding and (optionally) Claude's
      visual review check it for damage; damaged shipments are flagged in the core system.</p>

    <section class="card">
      <form class="row" (ngSubmit)="run()">
        <input type="file" accept="image/*" (change)="pick($event)">
        <select name="tracking" [(ngModel)]="trackingNumber">
          <option value="">No shipment (just analyse)</option>
          @for (s of shipments(); track s.id) { <option [value]="s.trackingNumber">{{ s.trackingNumber }} · {{ s.carrier }}</option> }
        </select>
        <label class="row"><input type="checkbox" name="llm" [(ngModel)]="useLlm"> Claude visual review</label>
        <button type="submit" [disabled]="!file || busy()">{{ busy() ? 'Inspecting…' : 'Inspect' }}</button>
      </form>
      @if (error()) { <div class="error">{{ error() }}</div> }
    </section>

    @if (result(); as r) {
      <div class="grid two">
        <section class="card">
          @if (r.annotated_image) { <img class="preview" [src]="'data:image/jpeg;base64,' + r.annotated_image" alt="Annotated inspection image"> }
        </section>
        <section class="card">
          <div class="verdict" [class.bad]="r.damaged" [class.good]="!r.damaged">{{ r.damaged ? 'Damage detected' : 'No damage detected' }}</div>
          <p>{{ r.summary }}</p>

          @if (r.llm_assessment; as a) {
            <h2>Claude review · <span class="badge" [class]="a.severity === 'none' ? 'ok' : 'CRITICAL'">{{ a.severity }}</span></h2>
            <ul>@for (f of a.findings; track $index) { <li>{{ f }}</li> }</ul>
            <p><strong>Recommended:</strong> {{ a.recommended_action }}</p>
          }

          @if (r.codes.length) { <h2>Labels read</h2><p>{{ r.codes.join(', ') }}</p> }

          <h2>Detections</h2>
          <table>
            <thead><tr><th>Label</th><th class="num">Count</th></tr></thead>
            <tbody>
              @for (c of r.item_counts | keyvalue; track c.key) { <tr><td>{{ c.key }}</td><td class="num">{{ c.value }}</td></tr> }
              @empty { <tr><td colspan="2" class="muted">No objects detected.</td></tr> }
            </tbody>
          </table>
          @if (r.detections.length) {
            <p class="muted">Top confidence: {{ maxConfidence(r) | percent: '1.0-0' }}</p>
          }
        </section>
      </div>
    }
  `,
})
export class Vision implements OnInit {
  private readonly api = inject(ApiService);

  /** Pre-selects a shipment when opened from the Shipments page (?tracking=...). */
  readonly tracking = input<string>();

  readonly shipments = signal<Shipment[]>([]);
  readonly result = signal<InspectionResult | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  file: File | null = null;
  trackingNumber = '';
  useLlm = false;

  ngOnInit() {
    this.trackingNumber = this.tracking() ?? '';
    this.api.shipments().subscribe(s => this.shipments.set(s));
  }

  pick(event: Event) {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
  }

  maxConfidence(r: InspectionResult) {
    return Math.max(...r.detections.map(d => d.confidence));
  }

  run() {
    if (!this.file) return;
    this.busy.set(true);
    this.error.set('');
    this.api.inspect(this.file, this.trackingNumber || null, this.useLlm).subscribe({
      next: r => { this.result.set(r); this.busy.set(false); },
      error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }
}
