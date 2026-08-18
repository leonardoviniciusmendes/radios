<script setup lang="ts">
const navItems = [
  { label: 'Dashboard', to: '/dashboard', icon: '▦' },
  { label: 'Rádios', to: '/radios', icon: '◉' },
  { label: 'Canais', to: '/canais', icon: '⌁' },
  { label: 'Configurações', to: '/configuracoes', icon: '⚙' },
];
</script>

<template>
  <aside class="sidebar">
    <div class="brand">
      <div class="brand-mark">R</div>
      <div>
        <strong>RadioPTT</strong>
        <span>Admin</span>
      </div>
    </div>

    <nav class="nav-list" aria-label="Menu principal">
      <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" class="nav-item">
        <span class="nav-icon">{{ item.icon }}</span>
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>
  </aside>
</template>
