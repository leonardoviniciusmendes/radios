<script setup lang="ts">
import { onMounted } from 'vue';
import SidebarNav from './components/SidebarNav.vue';
import { useRadioStore } from './stores/radioStore';

const radioStore = useRadioStore();

onMounted(() => {
  void radioStore.loadDevices();
});
</script>

<template>
  <div class="app-shell">
    <SidebarNav />
    <main class="main-content">
      <RouterView />
    </main>
  </div>
</template>
