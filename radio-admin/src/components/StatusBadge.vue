<script setup lang="ts">
import type { RadioStatus } from '../models/RadioDevice';

defineProps<{
  status: RadioStatus;
}>();
</script>

<template>
  <span class="status-badge" :class="status === 'ONLINE' ? 'status-online' : 'status-offline'">
    {{ status }}
  </span>
</template>
