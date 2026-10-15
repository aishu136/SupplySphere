import { Component, OnInit, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { ColdChainResult, Shipment, TemperatureProfile } from '../models';

@Component({
  selector: 'app-cold-chain',
  imports: [FormsModule],
  styles: `
    .preview { max-width: 100%; border-radius: 8px; border: 1px solid var(--border); }
    .verdict {
      font-size: 18px; font-weight: 650; margin-bottom: 8px;
      &.bad { color: var(--danger); }
      &.good { color: var(--ok); }
    }
    .scale input { width: 80px; }
  `,
  template: `
    <h1>Cold chain inspection</h1>
    <p class="muted">Check frozen and chilled food against its temperature range. Upload a <strong>thermal camera frame</strong>
      of a reefer trailer, cold room or pallet to find hot and cold spots, or an <strong>ordinary photo</strong> to look for frost,
      condensation and spoilage, and (with Claude) to read logger displays, temperature indicator labels and use-by dates.
      A breach quarantines the shipment and raises a COLD_CHAIN_BREACH alert.</p>

    <section class="card">
      <form (ngSubmit)="run()">
        <div class="row">
          <input type="file" accept="image/*" (change)="pick($event)">
          <select name="profile" [(ngModel)]="profile">
            @for (p of profiles(); track p.name) {
              <option [value]="p.name">{{ p.name }} · {{ p.min_c }} to {{ p.max_c }} °C</option>
            }
          </select>
          <select name="tracking" [(ngModel)]="trackingNumber">
            <option value="">No shipment (just analyse)</option>
            @for (s of shipments(); track s.id) { <option [value]="s.trackingNumber">{{ s.trackingNumber }} · {{ s.carrier }}</option> }
          </select>
        </div>
        <div class="row">
          <label class="row"><input type="checkbox" name="thermal" [(ngModel)]="thermal"> Thermal image</label>
          @if (thermal) {
            <label class="row scale">Scale <input type="number" name="min" [(ngModel)]="scaleMinC"> to
              <input type="number" name="max" [(ngModel)]="scaleMaxC"> °C</label>
            <select name="palette" [(ngModel)]="palette">
              <option value="inferno">Ironbow / inferno</option>
              <option value="grayscale">White-hot grayscale</option>
              <option value="jet">Rainbow (jet)</option>
              <option value="turbo">Turbo</option>
            </select>
          }
          <label class="row"><input type="checkbox" name="llm" [(ngModel)]="useLlm"> Claude review (displays, indicators, dates)</label>
          <button type="submit" [disabled]="!file || busy()">{{ busy() ? 'Inspecting…' : 'Inspect' }}</button>
        </div>
      </form>
      @if (thermal) {
        <p class="muted">Use the temperature range shown on the camera's colour bar, and crop the bar and on-screen text out of the frame.</p>
      }
      @if (error()) { <div class="error">{{ error() }}</div> }
    </section>

    @if (result(); as r) {
      @if (r.thermal; as t) {
        <div class="kpis">
          <div class="kpi" [class.bad]="t.max_c > r.profile.max_c"><div class="label">Max</div><div class="value">{{ t.max_c }} °C</div></div>
          <div class="kpi"><div class="label">Mean</div><div class="value">{{ t.mean_c }} °C</div></div>
          <div class="kpi"><div class="label">95th percentile</div><div class="value">{{ t.p95_c }} °C</div></div>
          <div class="kpi" [class.bad]="t.above_range_pct > 0"><div class="label">Above range</div><div class="value">{{ t.above_range_pct }}%</div></div>
          <div class="kpi" [class.bad]="t.below_range_pct > 0"><div class="label">Below range</div><div class="value">{{ t.below_range_pct }}%</div></div>
        </div>
      }
      <div class="grid two">
        <section class="card">
          @if (r.annotated_image) { <img class="preview" [src]="'data:image/jpeg;base64,' + r.annotated_image" alt="Annotated cold-chain inspection image"> }
        </section>
        <section class="card">
          <div class="verdict" [class.bad]="r.breach" [class.good]="!r.breach">
            {{ r.breach ? 'Cold-chain breach' : 'Within range' }}
            @if (r.breach) { <span class="badge" [class]="r.severity === 'critical' ? 'CRITICAL' : 'HIGH'">{{ r.severity }}</span> }
          </div>
          <p>{{ r.summary }}</p>

          @if (r.thermal?.spots?.length) {
            <h2>Hot and cold spots</h2>
            <table>
              <thead><tr><th>Kind</th><th class="num">Peak</th><th class="num">Area</th></tr></thead>
              <tbody>
                @for (s of r.thermal!.spots; track $index) {
                  <tr><td>{{ s.kind }}</td><td class="num">{{ s.peak_c }} °C</td><td class="num">{{ s.area_pct }}%</td></tr>
                }
              </tbody>
            </table>
          }

          @if (r.llm_assessment; as a) {
            <h2>Claude review</h2>
            <ul>
              @if (a.displayed_temperatures_c.length) { <li>Displays read: {{ a.displayed_temperatures_c.join(', ') }} °C</li> }
              <li>Temperature indicator: {{ a.indicator_status.replace('_', ' ') }}</li>
              @if (a.frost_or_ice) { <li>Frost or ice crystals</li> }
              @if (a.condensation) { <li>Condensation</li> }
              @if (a.spoilage_signs) { <li>Spoilage signs</li> }
              @if (a.dates_read.length) { <li>Dates: {{ a.dates_read.join(', ') }}</li> }
              @for (f of a.findings; track $index) { <li>{{ f }}</li> }
            </ul>
            <p><strong>Recommended:</strong> {{ a.recommended_action }}</p>
          }

          @if (r.codes.length) { <h2>Labels read</h2><p>{{ r.codes.join(', ') }}</p> }
        </section>
      </div>
    }
  `,
})
export class ColdChain implements OnInit {
  private readonly api = inject(ApiService);

  /** Pre-selects a shipment (?tracking=...). */
  readonly tracking = input<string>();

  readonly profiles = signal<TemperatureProfile[]>([]);
  readonly shipments = signal<Shipment[]>([]);
  readonly result = signal<ColdChainResult | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  file: File | null = null;
  profile = 'chilled';
  trackingNumber = '';
  thermal = false;
  scaleMinC = -30;
  scaleMaxC = 20;
  palette = 'inferno';
  useLlm = false;

  ngOnInit() {
    this.trackingNumber = this.tracking() ?? '';
    this.api.coldChainProfiles().subscribe(p => this.profiles.set(p));
    this.api.shipments().subscribe(s => this.shipments.set(s));
  }

  pick(event: Event) {
    this.file = (event.target as HTMLInputElement).files?.[0] ?? null;
  }

  run() {
    if (!this.file) return;
    this.busy.set(true);
    this.error.set('');
    this.api.inspectColdChain(this.file, {
      profile: this.profile, trackingNumber: this.trackingNumber || null, thermal: this.thermal,
      scaleMinC: this.scaleMinC, scaleMaxC: this.scaleMaxC, palette: this.palette, useLlm: this.useLlm,
    }).subscribe({
      next: r => { this.result.set(r); this.busy.set(false); },
      error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }
}
