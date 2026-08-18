import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { Channel } from '../models/Channel';
import type { RadioDevice, RadioDeviceConfigPayload } from '../models/RadioDevice';
import { radioPollingIntervalMs } from '../config/radioDevices';
import { radioApi } from '../services/radioApi';

export const useRadioStore = defineStore('radio', () => {
  const radios = ref<RadioDevice[]>([]);
  const channels = ref<Channel[]>([]);
  const selectedRadioId = ref<string | null>(null);
  let pollingTimer: number | undefined;

  const onlineRadios = computed(() => radios.value.filter((radio) => radio.status === 'ONLINE'));
  const offlineRadios = computed(() => radios.value.filter((radio) => radio.status === 'OFFLINE'));
  const activeChannels = computed(() => {
    const usedChannels = new Set(radios.value.map((radio) => radio.channel));
    return channels.value.filter((channel) => usedChannels.has(channel.name));
  });

  const selectedRadio = computed(() => {
    return radios.value.find((radio) => radio.id === selectedRadioId.value) ?? radios.value[0] ?? null;
  });

  function countRadiosByChannel(channelName: string) {
    return radios.value.filter((radio) => radio.channel === channelName).length;
  }

  function mergeRadios(nextRadios: RadioDevice[]) {
    radios.value = nextRadios.map((nextRadio) => {
      const currentRadio = radios.value.find((radio) => radio.id === nextRadio.id);
      if (!currentRadio || nextRadio.status === 'ONLINE') {
        return nextRadio;
      }

      return {
        ...currentRadio,
        status: 'OFFLINE',
        lastCommunication: 'Sem resposta',
      };
    });

    if (!selectedRadioId.value || !radios.value.some((radio) => radio.id === selectedRadioId.value)) {
      selectedRadioId.value = radios.value[0]?.id ?? null;
    }
  }

  async function refreshRadios() {
    const radioData = await radioApi.getRadios();
    mergeRadios(radioData);
  }

  function startPolling() {
    if (pollingTimer !== undefined) {
      return;
    }

    pollingTimer = window.setInterval(() => {
      void refreshRadios();
    }, radioPollingIntervalMs);
  }

  async function loadDevices() {
    const [radioData, channelData] = await Promise.all([radioApi.getRadios(), radioApi.getChannels()]);
    mergeRadios(radioData);
    channels.value = channelData;
    startPolling();
  }

  function selectRadio(id: string) {
    selectedRadioId.value = id;
  }

  function updateRadio(id: string, payload: Pick<RadioDevice, 'name' | 'channel' | 'enabled'>) {
    const index = radios.value.findIndex((radio) => radio.id === id);
    if (index >= 0) {
      radios.value[index] = { ...radios.value[index], ...payload };
    }
  }

  async function saveRadioConfig(id: string, payload: RadioDeviceConfigPayload) {
    const updatedRadio = await radioApi.updateRadioConfig(id, payload);
    const index = radios.value.findIndex((radio) => radio.id === id);
    if (index >= 0) {
      radios.value[index] = updatedRadio;
    }
  }

  function upsertChannel(channel: Channel) {
    const index = channels.value.findIndex((item) => item.id === channel.id);
    if (index >= 0) {
      channels.value[index] = channel;
      return;
    }

    channels.value.push(channel);
  }

  return {
    radios,
    channels,
    selectedRadioId,
    selectedRadio,
    onlineRadios,
    offlineRadios,
    activeChannels,
    countRadiosByChannel,
    loadDevices,
    refreshRadios,
    selectRadio,
    updateRadio,
    saveRadioConfig,
    upsertChannel,
  };
});
