<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import StatusBadge from '../components/StatusBadge.vue';
import { useRadioStore } from '../stores/radioStore';

const radioStore = useRadioStore();

const form = reactive({
  name: '',
  channel: '',
  enabled: true,
  saving: false,
  message: '',
  error: '',
});

const selectedRadio = computed(() => radioStore.selectedRadio);
const currentFormRadioId = ref<string | null>(null);

watch(
  selectedRadio,
  (radio) => {
    if (!radio) {
      return;
    }

    if (currentFormRadioId.value === radio.id) {
      return;
    }

    currentFormRadioId.value = radio.id;
    form.name = radio.name;
    form.channel = radio.channel;
    form.enabled = radio.enabled;
    form.message = '';
    form.error = '';
  },
  { immediate: true },
);

async function saveRadio() {
  if (!selectedRadio.value) {
    return;
  }

  form.saving = true;
  form.message = '';
  form.error = '';

  try {
    await radioStore.saveRadioConfig(selectedRadio.value.id, {
      name: form.name,
      channel: form.channel,
      enabled: form.enabled,
    });
    if (selectedRadio.value) {
      form.name = selectedRadio.value.name;
      form.channel = selectedRadio.value.channel;
      form.enabled = selectedRadio.value.enabled;
    }
    form.message = 'Configuração salva.';
  } catch {
    form.error = 'Não foi possível salvar a configuração do rádio.';
  } finally {
    form.saving = false;
  }
}
</script>

<template>
  <section class="page">
    <header class="page-header">
      <div>
        <p class="eyebrow">Dispositivos</p>
        <h1>Rádios</h1>
      </div>
    </header>

    <div class="radios-layout">
      <section class="panel table-panel">
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Nome</th>
                <th>Modelo</th>
                <th>Status</th>
                <th>IP</th>
                <th>Canal</th>
                <th>Última comunicação</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="radio in radioStore.radios"
                :key="radio.id"
                :class="{ selected: radio.id === radioStore.selectedRadioId }"
                @click="radioStore.selectRadio(radio.id)"
              >
                <td>{{ radio.name }}</td>
                <td>{{ radio.model }}</td>
                <td><StatusBadge :status="radio.status" /></td>
                <td>{{ radio.ip }}</td>
                <td>{{ radio.channel }}</td>
                <td>{{ radio.lastCommunication }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <aside v-if="selectedRadio" class="panel details-panel">
        <div class="panel-header">
          <div>
            <h2>{{ selectedRadio.name }}</h2>
            <span>{{ selectedRadio.deviceId }}</span>
          </div>
          <StatusBadge :status="selectedRadio.status" />
        </div>

        <div class="detail-grid">
          <label>
            Nome
            <input v-model="form.name" type="text" />
          </label>
          <label>
            Canal
            <select v-model="form.channel">
              <option v-for="channel in radioStore.channels" :key="channel.id" :value="channel.name">
                {{ channel.name }}
              </option>
            </select>
          </label>
          <label class="toggle-row">
            <span>Habilitado</span>
            <input v-model="form.enabled" type="checkbox" />
          </label>
        </div>

        <dl class="info-list">
          <div><dt>Modelo</dt><dd>{{ selectedRadio.model }}</dd></div>
          <div><dt>IP atual</dt><dd>{{ selectedRadio.ip }}</dd></div>
          <div><dt>MAC</dt><dd>{{ selectedRadio.mac }}</dd></div>
          <div><dt>Versão do aplicativo</dt><dd>{{ selectedRadio.appVersion }}</dd></div>
          <div><dt>Wi-Fi</dt><dd>{{ selectedRadio.wifi }}</dd></div>
          <div><dt>Qualidade do sinal</dt><dd>{{ selectedRadio.signalQuality }}%</dd></div>
          <div><dt>Bateria</dt><dd>{{ selectedRadio.battery }}%</dd></div>
          <div><dt>Última comunicação</dt><dd>{{ selectedRadio.lastCommunication }}</dd></div>
        </dl>

        <p v-if="form.message" class="form-feedback success-feedback">{{ form.message }}</p>
        <p v-if="form.error" class="form-feedback error-feedback">{{ form.error }}</p>

        <div class="action-row">
          <button class="primary-button" type="button" :disabled="form.saving" @click="saveRadio">
            {{ form.saving ? 'Salvando...' : 'Salvar configuração' }}
          </button>
          <button class="secondary-button" type="button">Reiniciar rádio</button>
        </div>
      </aside>
    </div>
  </section>
</template>
