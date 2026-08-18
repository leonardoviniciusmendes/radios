<script setup lang="ts">
import { reactive } from 'vue';
import { useRadioStore } from '../stores/radioStore';

const radioStore = useRadioStore();
const form = reactive({ id: '', name: '', description: '' });

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
          <article v-for="channel in radioStore.channels" :key="channel.id" class="channel-card">
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
  </section>
</template>
