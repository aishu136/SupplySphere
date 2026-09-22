import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  { path: 'dashboard', title: 'Dashboard', loadComponent: () => import('./pages/dashboard').then(m => m.Dashboard) },
  { path: 'inventory', title: 'Inventory', loadComponent: () => import('./pages/inventory').then(m => m.Inventory) },
  { path: 'orders', title: 'Purchase orders', loadComponent: () => import('./pages/orders').then(m => m.Orders) },
  { path: 'shipments', title: 'Shipments', loadComponent: () => import('./pages/shipments').then(m => m.Shipments) },
  { path: 'replenishment', title: 'RL replenishment', loadComponent: () => import('./pages/replenishment').then(m => m.Replenishment) },
  { path: 'assistant', title: 'AI assistant', loadComponent: () => import('./pages/assistant').then(m => m.Assistant) },
  { path: 'vision', title: 'Vision inspection', loadComponent: () => import('./pages/vision').then(m => m.Vision) },
  { path: '**', redirectTo: 'dashboard' },
];
