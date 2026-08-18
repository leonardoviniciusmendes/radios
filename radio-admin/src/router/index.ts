import { createRouter, createWebHistory } from 'vue-router';
import DashboardView from '../views/DashboardView.vue';
import RadiosView from '../views/RadiosView.vue';
import ChannelsView from '../views/ChannelsView.vue';
import SettingsView from '../views/SettingsView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', name: 'dashboard', component: DashboardView },
    { path: '/radios', name: 'radios', component: RadiosView },
    { path: '/canais', name: 'channels', component: ChannelsView },
    { path: '/configuracoes', name: 'settings', component: SettingsView },
  ],
});

export default router;
