import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="shell">
      <nav class="sidebar">
        <div class="brand">SCM Control Tower</div>
        @for (item of nav; track item.path) {
          <a [routerLink]="item.path" routerLinkActive="active">{{ item.label }}</a>
        }
      </nav>
      <main class="content">
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
