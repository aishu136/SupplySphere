import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { DockDoorMonitor, DockDoorReport, DoorRoi } from '../models';

@Component({
  selector: 'app-dock-doors',
  imports: [FormsModule],
  styles: `
    .stage { position: relative; display: inline-block; max-width: 100%; }
    .stage video { display: block; max-width: 100%; border-radius: 8px; border: 1px solid var(--border); }
    .roi { position: absolute; border: 2px solid var(--danger); pointer-events: none; }
    .roi input { width: 64px; }
    .timeline { position: relative; height: 22px; border-radius: 4px; background: var(--border); overflow: hidden; margin: 8px 0 4px; }
    .seg { position: absolute; top: 0; bottom: 0; background: var(--warn);
      &.violation { background: var(--danger); } }
    .keyframe { max-width: 100%; border-radius: 8px; border: 1px solid var(--border); }
    .roi-fields input { width: 70px; }
  `,
  template: `
    <h1>Reefer dock doors</h1>
    <p class="muted">Times how long reefer dock doors stay open, from video. Mark the door in the frame; the door must be
      closed at the start of the clip (or upload a still of it closed). Openings longer than the limit are violations
      and can raise a DOCK_DOOR_OPEN alert. People or forklifts passing for under two seconds don't count.</p>

    <section class="card">
      <h2>Analyse a recorded clip</h2>
      <form (ngSubmit)="analyze()">
        <div class="row">
          <label>Video <input type="file" accept="video/*" (change)="pickVideo($event)"></label>
          <label>Closed-door still (optional) <input type="file" accept="image/*" (change)="pickReference($event)"></label>
        </div>
        <div class="row roi-fields">
          <label>Door <input name="door" [(ngModel)]="doorId" placeholder="WH-EAST/D3"></label>
          <label>Door region (% of frame): x <input type="number" name="x" min="0" max="99" [(ngModel)]="roi.x"></label>
          <label>y <input type="number" name="y" min="0" max="99" [(ngModel)]="roi.y"></label>
          <label>width <input type="number" name="w" min="1" max="100" [(ngModel)]="roi.w"></label>
          <label>height <input type="number" name="h" min="1" max="100" [(ngModel)]="roi.h"></label>
          <label>Limit <input type="number" name="limit" min="1" [(ngModel)]="maxOpenS"> s</label>
          <label class="row"><input type="checkbox" name="alerts" [(ngModel)]="raiseAlerts"> Raise alerts</label>
          <button type="submit" [disabled]="!video || !doorId || busy()">{{ busy() ? 'Analysing…' : 'Analyse' }}</button>
        </div>
      </form>
      @if (videoUrl(); as url) {
        <div class="stage">
          <video [src]="url" controls muted></video>
          <div class="roi" [style.left.%]="roi.x" [style.top.%]="roi.y" [style.width.%]="roi.w" [style.height.%]="roi.h"></div>
        </div>
      }
      @if (error()) { <div class="error">{{ error() }}</div> }
    </section>

    @if (report(); as r) {
      <div class="kpis">
        <div class="kpi"><div class="label">Openings</div><div class="value">{{ r.open_count }}</div></div>
        <div class="kpi"><div class="label">Total open</div><div class="value">{{ r.total_open_s }} s</div></div>
        <div class="kpi" [class.bad]="r.longest_open_s > r.max_open_s"><div class="label">Longest</div><div class="value">{{ r.longest_open_s }} s</div></div>
        <div class="kpi"><div class="label">Open time</div><div class="value">{{ r.open_pct }}%</div></div>
        <div class="kpi" [class.bad]="r.violations > 0"><div class="label">Over {{ r.max_open_s }} s</div><div class="value">{{ r.violations }}</div></div>
      </div>
      <div class="grid two">
        <section class="card">
          <h2>{{ r.door_id }} · {{ r.duration_s }} s of video</h2>
          <div class="timeline" role="img" [attr.aria-label]="r.open_count + ' openings over ' + r.duration_s + ' seconds'">
            @for (i of r.open_intervals; track $index) {
              <div class="seg" [class.violation]="i.violation"
                   [style.left.%]="100 * i.start_s / r.duration_s" [style.width.%]="100 * i.duration_s / r.duration_s"
                   [title]="i.start_s + '–' + i.end_s + ' s'"></div>
            }
          </div>
          <p class="muted">Open periods, red where they went over the limit.</p>
          <table>
            <thead><tr><th>From</th><th>To</th><th class="num">Open</th><th></th></tr></thead>
            <tbody>
              @for (i of r.open_intervals; track $index) {
                <tr>
                  <td>{{ clock(i.start_s) }}</td><td>{{ clock(i.end_s) }}{{ i.ongoing ? ' (still open)' : '' }}</td>
                  <td class="num">{{ i.duration_s }} s</td>
                  <td>@if (i.violation) { <span class="badge CRITICAL">over limit</span> }</td>
                </tr>
              } @empty { <tr><td colspan="4" class="muted">The door stayed closed.</td></tr> }
            </tbody>
          </table>
        </section>
        <section class="card">
          @if (r.keyframe) {
            <h2>Longest opening</h2>
            <img class="keyframe" [src]="'data:image/jpeg;base64,' + r.keyframe" alt="Frame from the longest door opening">
          }
        </section>
      </div>
    }

    <section class="card">
      <h2>Live monitors</h2>
      <p class="muted">Watches a camera stream (rtsp, rtsps, http or https), using the door region and limit above.
        An alert is raised once per opening that goes over the limit.</p>
      <form class="row" (ngSubmit)="startMonitor()">
        <input name="source" [(ngModel)]="source" placeholder="rtsp://camera-d3.local/stream" class="flex-1">
        <button type="submit" [disabled]="!source || !doorId">Start monitoring {{ doorId || 'door' }}</button>
      </form>
      <table>
        <thead><tr><th>Door</th><th>Status</th><th>Door</th><th class="num">Open for</th><th class="num">Openings</th><th class="num">Alerts</th><th></th></tr></thead>
        <tbody>
          @for (m of activeMonitors(); track m.id) {
            <tr>
              <td>{{ m.doorId }}</td>
              <td>{{ m.status }}@if (m.error) { <span class="muted"> · {{ m.error }}</span> }</td>
              <td><span class="badge" [class]="m.doorOpen ? (m.openForS > m.maxOpenS ? 'CRITICAL' : 'HIGH') : 'ok'">{{ m.doorOpen ? 'open' : 'closed' }}</span></td>
              <td class="num">{{ m.doorOpen ? m.openForS + ' s' : '—' }}</td>
              <td class="num">{{ m.openings }}</td>
              <td class="num">{{ m.alertsRaised }}</td>
              <td><button class="secondary" (click)="stopMonitor(m.id)">Stop</button></td>
            </tr>
          } @empty { <tr><td colspan="7" class="muted">No live monitors.</td></tr> }
        </tbody>
      </table>
    </section>
  `,
})
export class DockDoors implements OnInit, OnDestroy {
  private readonly api = inject(ApiService);
  private poll?: ReturnType<typeof setInterval>;

  readonly report = signal<DockDoorReport | null>(null);
  readonly monitors = signal<DockDoorMonitor[]>([]);
  readonly activeMonitors = computed(() => this.monitors().filter(m => m.status !== 'stopped'));
  readonly videoUrl = signal<string | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  video: File | null = null;
  reference: File | null = null;
  doorId = '';
  roi: DoorRoi = { x: 25, y: 15, w: 50, h: 80 };
  maxOpenS = 120;
  raiseAlerts = false;
  source = '';

  ngOnInit() {
    this.loadMonitors();
    this.poll = setInterval(() => this.loadMonitors(), 3000);
  }

  ngOnDestroy() {
    clearInterval(this.poll);
    const url = this.videoUrl();
    if (url) URL.revokeObjectURL(url);
  }

  pickVideo(event: Event) {
    this.video = (event.target as HTMLInputElement).files?.[0] ?? null;
    const old = this.videoUrl();
    if (old) URL.revokeObjectURL(old);
    this.videoUrl.set(this.video ? URL.createObjectURL(this.video) : null);
  }

  pickReference(event: Event) {
    this.reference = (event.target as HTMLInputElement).files?.[0] ?? null;
  }

  clock(s: number) {
    const m = Math.floor(s / 60);
    return `${m}:${(s - m * 60).toFixed(0).padStart(2, '0')}`;
  }

  analyze() {
    if (!this.video) return;
    this.busy.set(true);
    this.error.set('');
    this.api.analyzeDockDoor(this.video, this.doorId, this.roiFraction(), this.maxOpenS, this.reference, this.raiseAlerts)
      .subscribe({
        next: r => { this.report.set(r); this.busy.set(false); },
        error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
      });
  }

  startMonitor() {
    this.error.set('');
    this.api.startDockDoorMonitor({ door_id: this.doorId, source: this.source, roi: this.roiFraction(), max_open_s: this.maxOpenS })
      .subscribe({ next: () => { this.source = ''; this.loadMonitors(); }, error: e => this.error.set(errorMessage(e)) });
  }

  stopMonitor(id: string) {
    this.api.stopDockDoorMonitor(id).subscribe(() => this.loadMonitors());
  }

  private loadMonitors() {
    this.api.dockDoorMonitors().subscribe({ next: m => this.monitors.set(m), error: () => {} });
  }

  /** The form uses percentages; the API takes fractions of the frame. */
  private roiFraction(): DoorRoi {
    return { x: this.roi.x / 100, y: this.roi.y / 100, w: this.roi.w / 100, h: this.roi.h / 100 };
  }
}
