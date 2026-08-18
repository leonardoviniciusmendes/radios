<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { useRadioStore } from '../stores/radioStore';

const radioStore = useRadioStore();
const form = reactive({ id: '', name: '', description: '' });
const selectedChannelId = ref<string | null>(null);
const selectedChannel = computed(() => {
  return radioStore.channels.find((channel) => channel.id === selectedChannelId.value) ?? radioStore.channels[0] ?? null;
});
const selectedChannelRadios = computed(() => {
  if (!selectedChannel.value) {
    return [];
  }

  return radioStore.radiosByChannel(selectedChannel.value.name);
});

function editChannel(id: string) {
  const channel = radioStore.channels.find((item) => item.id === id);
  if (!channel) {
    return;
  }

  form.id = channel.id;
  form.name = channel.name;
  form.description = channel.description ?? '';
}

function saveChannel() {
  const name = form.name.trim();
  if (!name) {
    return;
  }

  radioStore.upsertChannel({
    id: form.id || name.toLowerCase().normalize('NFD').replace(/\p{Diacritic}/gu, '').replace(/\s+/g, '-'),
    name,
    description: form.description.trim(),
  });

  form.id = '';
  form.name = '';
  form.description = '';
}

function selectChannel(id: string) {
  selectedChannelId.value = id;
}
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Organização</p>
        <h1>Canais</h1>
      </div>
    </header>

    <div class="content-grid">
      <section class="panel">
        <div class="panel-header">
          <h2>Canais cadastrados</h2>
        </div>

        <div class="channel-cards">
          <article v-for="channel in radioStore.channels" :key="channel.id" class="channel-card" @click="selectChannel(channel.id)">
            <div>
              <strong>{{ channel.name }}</strong>
              <span>{{ channel.description }}</span>
            </div>
            <div class="channel-card-actions">
              <span>{{ radioStore.countRadiosByChannel(channel.name) }} rádios</span>
              <button class="ghost-button" type="button" @click="editChannel(channel.id)">Editar</button>
            </div>
          </article>
        </div>
      </section>

      <aside class="panel">
        <div class="panel-header">
          <h2>{{ form.id ? 'Editar canal' : 'Novo canal' }}</h2>
        </div>

        <div class="form-stack">
          <label>
            Nome do canal
            <input v-model="form.name" type="text" placeholder="Ex.: Operação" />
          </label>
          <label>
            Descrição
            <textarea v-model="form.description" rows="4" placeholder="Uso interno do canal"></textarea>
          </label>
          <button class="primary-button" type="button" @click="saveChannel">Salvar canal</button>
        </div>
      </aside>
    </div>

    <section v-if="selectedChannel" class="panel">
      <div class="panel-header">
        <h2>{{ selectedChannel.name }}</h2>
        <span>{{ selectedChannelRadios.length }} rádios</span>
      </div>

      <div class="compact-list">
        <div v-for="radio in selectedChannelRadios" :key="radio.id" class="compact-row">
          <div>
            <strong>{{ radio.name }}</strong>
            <span>{{ radio.model }} · {{ radio.ip }}</span>
          </div>
          <span>{{ radio.status }}</span>
        </div>
        <div v-if="selectedChannelRadios.length === 0" class="compact-row">
          <div>
            <strong>Nenhum rádio</strong>
            <span>Sem rádios neste canal</span>
          </div>
        </div>
      </div>
    </section>
  </section>
</template>
