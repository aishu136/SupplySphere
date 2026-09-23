import { DatePipe, JsonPipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';
import { Alert, ExceptionWorkflow, WorkflowAction } from '../models';

const SUPPORTED = ['LOW_STOCK', 'SHIPMENT_DELAYED'];

@Component({
  selector: 'app-exceptions',
  imports: [FormsModule, DatePipe, JsonPipe],
  styles: `
    .run { border-left: 4px solid var(--border); }
    .run.awaiting_approval { border-left-color: var(--warn); }
    .run.completed { border-left-color: var(--ok); }
    .run.rejected, .run.partially_failed { border-left-color: var(--danger); }
    .rationale { white-space: pre-wrap; line-height: 1.45; }
    details pre { max-height: 240px; overflow: auto; font-size: 12px; }
  `,
  template: `
    <h1>Exception workflows</h1>
    <p class="muted">LangGraph workflows resolve LOW_STOCK and SHIPMENT_DELAYED alerts. Each one gathers live context
      and the RL recommendation, drafts a plan with Claude (or a rule-based plan if Claude is unavailable), then
      <strong>pauses for your approval</strong> before changing anything. Flink alerts start workflows automatically.</p>
    @if (error()) { <div class="error">{{ error() }}</div> }

    @if (startable().length) {
      <section class="card">
        <h2>Alerts without a workflow</h2>
        <table>
          <tbody>
            @for (a of startable(); track a.alertId) {
              <tr>
                <td><span class="badge" [class]="a.severity">{{ a.type }}</span></td>
                <td style="white-space: normal">{{ a.message }}</td>
                <td><button class="secondary" (click)="start(a)" [disabled]="busy()">Start workflow</button></td>
              </tr>
            }
          </tbody>
        </table>
      </section>
    }

    @for (r of runs(); track r.threadId) {
      <section class="card run" [class]="r.status">
        <div class="row" style="justify-content: space-between">
          <div class="row">
            <span class="badge" [class]="badge(r.status)">{{ r.status.replace('_', ' ') }}</span>
            <strong>{{ r.alert.type }}</strong><span>{{ r.alert.entityId }}</span>
            @if (r.planner) { <span class="muted">planned by {{ r.planner === 'claude' ? 'Claude' : 'rules (Claude unavailable)' }}</span> }
          </div>
          <span class="muted">{{ r.updatedAt | date: 'MMM d, HH:mm' }}</span>
        </div>
        <p class="muted">{{ r.alert.message }}</p>

        @if (r.plan; as plan) {
          <p><strong>{{ plan.summary }}</strong></p>
          <p class="rationale">{{ plan.rationale }}</p>
          @if (plan.actions.length) {
            <div class="table-wrap"><table>
              <thead><tr><th>Action</th><th>Target</th><th class="num">Quantity / status</th><th>Reason</th></tr></thead>
              <tbody>
                @for (a of editable(r); track $index) {
                  <tr>
                    <td>{{ a.type === 'create_purchase_order' ? 'Create PO' : 'Update shipment' }}</td>
                    <td>{{ a.type === 'create_purchase_order' ? a.sku + ' @ ' + a.warehouse_code : a.tracking_number }}</td>
                    <td class="num">
                      @if (a.type === 'create_purchase_order' && r.status === 'awaiting_approval') {
                        <input type="number" min="1" [(ngModel)]="a.quantity" style="width: 90px">
                      } @else {
                        {{ a.type === 'create_purchase_order' ? a.quantity : a.status }}
                      }
                    </td>
                    <td style="white-space: normal">{{ a.reason }}</td>
                  </tr>
                }
              </tbody>
            </table></div>
          }
        }

        @if (r.status === 'awaiting_approval') {
          <div class="row" style="margin-top: 12px">
            <input placeholder="Comment (optional)" [(ngModel)]="comments[r.threadId]" style="min-width: 280px">
            <button (click)="decide(r, true)" [disabled]="busy()">Approve and execute</button>
            <button class="secondary" (click)="decide(r, false)" [disabled]="busy()">Reject</button>
          </div>
        }
        @if (r.outcome) { <p><strong>Outcome:</strong> {{ r.outcome }}</p> }
        @if (r.context) {
          <details><summary class="muted">Context gathered</summary><pre>{{ r.context | json }}</pre></details>
        }
      </section>
    } @empty {
      <section class="card muted">No workflows yet. They start automatically from Flink alerts, or from the list above.</section>
    }
  `,
})
export class Exceptions implements OnInit {
  private readonly api = inject(ApiService);

  readonly runs = signal<ExceptionWorkflow[]>([]);
  readonly alerts = signal<Alert[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');
  comments: Record<string, string> = {};
  private edits: Record<string, WorkflowAction[]> = {};

  readonly startable = computed(() => {
    const started = new Set(this.runs().map(r => r.threadId));
    return this.alerts().filter(a => SUPPORTED.includes(a.type) && !started.has('exception-' + a.alertId)).slice(0, 10);
  });

  ngOnInit() { this.load(); }

  load() {
    this.api.exceptionWorkflows().subscribe({ next: r => this.runs.set(r), error: e => this.error.set(errorMessage(e)) });
    this.api.alerts().subscribe(a => this.alerts.set(a));
  }

  /** Per-run copy of the proposed actions, so quantities can be edited before approval. */
  editable(r: ExceptionWorkflow): WorkflowAction[] {
    const source = r.decision?.actions ?? r.plan?.actions ?? [];
    if (r.status !== 'awaiting_approval') return source;
    return this.edits[r.threadId] ??= source.map(a => ({ ...a }));
  }

  badge(status: string) {
    return { awaiting_approval: 'MEDIUM', completed: 'ok', rejected: 'CANCELLED', partially_failed: 'CRITICAL' }[status] ?? '';
  }

  start(alert: Alert) {
    this.run(this.api.startExceptionWorkflow(alert));
  }

  decide(r: ExceptionWorkflow, approved: boolean) {
    this.run(this.api.decideExceptionWorkflow(r.threadId, approved, approved ? this.editable(r) : null,
      this.comments[r.threadId] ?? ''));
  }

  private run(call: ReturnType<ApiService['startExceptionWorkflow']>) {
    this.busy.set(true);
    this.error.set('');
    call.subscribe({
      next: () => { this.busy.set(false); this.load(); },
      error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
    });
  }
}
