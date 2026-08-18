<script setup lang="ts">
defineProps<{
  label: string;
  value: number;
  tone?: 'green' | 'red' | 'blue' | 'amber';
}>();
</script>

<template>
  <article class="metric-card" :class="tone ? `metric-${tone}` : ''">
    <span>{{ label }}</span>
    <strong>{{ value }}</strong>
  </article>
</template>
