import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, errorMessage } from '../api.service';

interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
  tools?: string[];
}

@Component({
  selector: 'app-assistant',
  imports: [FormsModule],
  styles: `
    .chat { display: flex; flex-direction: column; gap: 12px; min-height: 360px; max-height: 62vh; overflow-y: auto; }
    .msg { max-width: 80%; padding: 10px 14px; border-radius: 10px; white-space: pre-wrap; line-height: 1.45; }
    .user { align-self: flex-end; background: var(--accent); color: var(--accent-text); }
    .assistant { align-self: flex-start; background: var(--bg); border: 1px solid var(--border); }
    .tools { font-size: 11px; color: var(--muted); margin-top: 6px; }
    .composer { display: flex; gap: 10px; margin-top: 14px; }
    .composer textarea { flex: 1; resize: vertical; min-height: 44px; }
  `,
  template: `
    <h1>AI assistant</h1>
    <p class="muted">Claude on Amazon Bedrock, using supply chain tools served over MCP and a RAG index of contracts and SOPs.</p>

    <section class="card">
      <div class="chat">
        @if (messages().length === 0) {
          <div class="row">
            @for (s of suggestions; track s) { <button class="secondary" (click)="send(s)">{{ s }}</button> }
          </div>
        }
        @for (m of messages(); track $index) {
          <div class="msg" [class]="m.role">
            {{ m.text }}
            @if (m.tools?.length) { <div class="tools">Tools used: {{ m.tools!.join(', ') }}</div> }
          </div>
        }
        @if (busy()) { <div class="msg assistant muted">Thinking…</div> }
      </div>
      @if (error()) { <div class="error">{{ error() }}</div> }
      <form class="composer" (ngSubmit)="send(draft)">
        <textarea name="draft" [(ngModel)]="draft" placeholder="Ask about stock, orders, shipments or policies…"
                  (keydown.enter)="$event.preventDefault(); send(draft)"></textarea>
        <button type="submit" [disabled]="busy() || !draft.trim()">Send</button>
      </form>
    </section>
  `,
})
export class Assistant {
  private readonly api = inject(ApiService);
  private readonly sessionId = crypto.randomUUID();

  readonly messages = signal<ChatMessage[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');
  draft = '';

  readonly suggestions = [
    'Which items are below their reorder point, and what does the reorder policy say to do?',
    'Which shipments are late, and what penalties apply under the supplier contract?',
    'Summarise today\'s alerts and recommend the three most urgent actions.',
  ];

  send(text: string) {
    text = text.trim();
    if (!text || this.busy()) return;
    this.draft = '';
    this.error.set('');
    this.messages.update(m => [...m, { role: 'user', text }]);
    this.busy.set(true);
    this.api.chat(this.sessionId, text).subscribe({
      next: r => this.messages.update(m => [...m, { role: 'assistant', text: r.reply, tools: r.tools_used }]),
      error: e => { this.error.set(errorMessage(e)); this.busy.set(false); },
      complete: () => this.busy.set(false),
    });
  }
}
