import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="grid min-h-screen grid-cols-1 md:grid-cols-[220px_1fr]">
      <nav class="flex gap-1 overflow-x-auto bg-sidebar px-4 py-2.5 md:flex-col md:px-3 md:py-5">
        <div class="hidden px-2.5 pb-[18px] text-base font-bold text-white md:block">SCM Control Tower</div>
        @for (item of nav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="bg-accent! text-white!"
             class="rounded-md px-2.5 py-[9px] whitespace-nowrap text-sidebar-ink no-underline hover:bg-white/6">{{ item.label }}</a>
        }
      </nav>
      <main class="min-w-0 p-4 md:px-7 md:py-6">
        <router-outlet />
      </main>
    </div>
  `,
})
export class App {
  readonly nav = [
    { path: '/dashboard', label: 'Dashboard' },
    { path: '/inventory', label: 'Inventory' },
    { path: '/orders', label: 'Purchase orders' },
    { path: '/shipments', label: 'Shipments' },
    { path: '/exceptions', label: 'Exception workflows' },
    { path: '/replenishment', label: 'RL replenishment' },
    { path: '/assistant', label: 'AI assistant' },
    { path: '/vision', label: 'Vision inspection' },
  ];
}
