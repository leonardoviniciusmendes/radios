<script setup lang="ts">
import MetricCard from '../components/MetricCard.vue';
import StatusBadge from '../components/StatusBadge.vue';
import { useRadioStore } from '../stores/radioStore';

const radioStore = useRadioStore();
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Operação</p>
        <h1>Dashboard</h1>
      </div>
      <span class="header-pill">MVP administrativo</span>
    </header>

    <div class="metrics-grid">
      <MetricCard label="Rádios online" :value="radioStore.onlineRadios.length" tone="green" />
      <MetricCard label="Rádios offline" :value="radioStore.offlineRadios.length" tone="red" />
      <MetricCard label="Total de rádios" :value="radioStore.radios.length" tone="blue" />
      <MetricCard label="Canais ativos" :value="radioStore.activeChannels.length" tone="amber" />
    </div>

    <div class="content-grid">
      <section class="panel">
        <div class="panel-header">
          <h2>Rádios monitorados</h2>
          <RouterLink to="/radios" class="text-link">Ver todos</RouterLink>
        </div>

        <div class="compact-list">
          <div v-for="radio in radioStore.radios" :key="radio.id" class="compact-row">
            <div>
              <strong>{{ radio.name }}</strong>
              <span>{{ radio.model }} · {{ radio.ip }}</span>
            </div>
            <StatusBadge :status="radio.status" />
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-header">
          <h2>Canais</h2>
          <RouterLink to="/canais" class="text-link">Gerenciar</RouterLink>
        </div>

        <div class="channel-stack">
          <div v-for="channel in radioStore.channels" :key="channel.id" class="channel-line">
            <span>{{ channel.name }}</span>
            <strong>{{ radioStore.countRadiosByChannel(channel.name) }}</strong>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>
