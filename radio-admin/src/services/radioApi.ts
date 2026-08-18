import axios from 'axios';
import { radioRequestTimeoutMs } from '../config/radioDevices';
import type { Channel } from '../models/Channel';
import type {
  RadioDevice,
  RadioDeviceApiResponse,
  RadioDeviceConfigPayload,
} from '../models/RadioDevice';
import type { RadioDiscovery } from './discoveryBridgeApi';
import { discoveryBridgeApi } from './discoveryBridgeApi';

const api = axios.create({
  timeout: radioRequestTimeoutMs,
});

const mockChannels: Channel[] = [
  { id: 'geral', name: 'Geral', description: 'Canal principal da operação' },
  { id: 'portaria', name: 'Portaria', description: 'Equipe de acesso e recepção' },
  { id: 'seguranca', name: 'Segurança', description: 'Equipe de ronda e vigilância' },
  { id: 'manutencao', name: 'Manutenção', description: 'Equipe técnica e apoio' },
];

function getDeviceUrl(ip: string, httpPort: number) {
  return `http://${ip}:${httpPort}/api/device`;
}

function getDeviceConfigUrl(ip: string, httpPort: number) {
  return `http://${ip}:${httpPort}/api/device/config`;
}

function mapOnlineDevice(discovery: RadioDiscovery, device: RadioDeviceApiResponse): RadioDevice {
  return {
    id: device.deviceId || discovery.deviceId,
    name: device.name || discovery.name || `Radio ${discovery.deviceId}`,
    deviceId: device.deviceId || discovery.deviceId,
    model: device.model || 'unavailable',
    ip: device.ip || discovery.ip,
    httpPort: discovery.httpPort,
    mac: device.mac || 'unavailable',
    status: device.online && discovery.online ? 'ONLINE' : 'OFFLINE',
    channel: device.channel || discovery.channel || 'Geral',
    appVersion: device.appVersion || 'unavailable',
    wifi: device.wifi || 'unavailable',
    signalQuality: Number.isFinite(device.signal) ? device.signal : 0,
    battery: Number.isFinite(device.battery) ? device.battery : 0,
    lastCommunication: new Date().toLocaleTimeString('pt-BR', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }),
    enabled: device.enabled ?? true,
  };
}

function mapOfflineDevice(discovery: RadioDiscovery): RadioDevice {
  return {
    id: discovery.deviceId,
    name: discovery.name || `Radio ${discovery.deviceId}`,
    deviceId: discovery.deviceId,
    model: discovery.model || 'unavailable',
    ip: discovery.ip,
    httpPort: discovery.httpPort,
    mac: 'unavailable',
    status: 'OFFLINE',
    channel: discovery.channel || 'Geral',
    appVersion: 'unavailable',
    wifi: 'unavailable',
    signalQuality: 0,
    battery: 0,
    lastCommunication: 'Sem resposta',
    enabled: true,
  };
}

async function getRadioByDiscovery(discovery: RadioDiscovery): Promise<RadioDevice> {
  if (!discovery.online) {
    return mapOfflineDevice(discovery);
  }

  try {
    const response = await api.get<RadioDeviceApiResponse>(getDeviceUrl(discovery.ip, discovery.httpPort));
    console.log(`RADIO_DEVICE_LOADED deviceId=${response.data.deviceId || discovery.deviceId} ip=${response.data.ip || discovery.ip}`);
    return mapOnlineDevice(discovery, response.data);
  } catch {
    return mapOfflineDevice(discovery);
  }
}

export const radioApi = {
  client: api,

  async getRadios(): Promise<RadioDevice[]> {
    const discoveredRadios = await discoveryBridgeApi.getRadios();
    return Promise.all(discoveredRadios.map((radio) => getRadioByDiscovery(radio)));
  },

  async updateRadioConfig(radio: RadioDevice, payload: RadioDeviceConfigPayload): Promise<RadioDevice> {
    const httpPort = radio.httpPort ?? 50080;
    await api.put(getDeviceConfigUrl(radio.ip, httpPort), payload, {
      headers: {
        'Content-Type': 'application/json',
      },
    });
    const discovery: RadioDiscovery = {
      deviceId: radio.deviceId,
      name: radio.name,
      model: radio.model,
      ip: radio.ip,
      httpPort,
      channel: payload.channel,
      lastSeen: new Date().toISOString(),
      online: true,
    };
    return getRadioByDiscovery(discovery);
  },

  async getChannels(): Promise<Channel[]> {
    return structuredClone(mockChannels);
  },
};
