import axios from 'axios';
import {
  radioDeviceIps,
  radioHttpPort,
  radioRequestTimeoutMs,
} from '../config/radioDevices';
import type { Channel } from '../models/Channel';
import type {
  RadioDevice,
  RadioDeviceApiResponse,
  RadioDeviceConfigPayload,
} from '../models/RadioDevice';

const api = axios.create({
  timeout: radioRequestTimeoutMs,
});

const mockChannels: Channel[] = [
  { id: 'geral', name: 'Geral', description: 'Canal principal da operação' },
  { id: 'portaria', name: 'Portaria', description: 'Equipe de acesso e recepção' },
  { id: 'seguranca', name: 'Segurança', description: 'Equipe de ronda e vigilância' },
  { id: 'manutencao', name: 'Manutenção', description: 'Equipe técnica e apoio' },
];

function getDeviceUrl(ip: string) {
  return `http://${ip}:${radioHttpPort}/api/device`;
}

function getDeviceConfigUrl(ip: string) {
  return `http://${ip}:${radioHttpPort}/api/device/config`;
}

function mapOnlineDevice(ip: string, device: RadioDeviceApiResponse): RadioDevice {
  return {
    id: ip,
    name: device.name || `Radio ${ip}`,
    deviceId: device.deviceId || ip,
    model: device.model || 'unavailable',
    ip: device.ip || ip,
    mac: device.mac || 'unavailable',
    status: device.online ? 'ONLINE' : 'OFFLINE',
    channel: device.channel || 'Geral',
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

function mapOfflineDevice(ip: string): RadioDevice {
  return {
    id: ip,
    name: `Radio ${ip}`,
    deviceId: ip,
    model: 'unavailable',
    ip,
    mac: 'unavailable',
    status: 'OFFLINE',
    channel: 'Geral',
    appVersion: 'unavailable',
    wifi: 'unavailable',
    signalQuality: 0,
    battery: 0,
    lastCommunication: 'Sem resposta',
    enabled: true,
  };
}

async function getRadioByIp(ip: string): Promise<RadioDevice> {
  try {
    const response = await api.get<RadioDeviceApiResponse>(getDeviceUrl(ip));
    return mapOnlineDevice(ip, response.data);
  } catch {
    return mapOfflineDevice(ip);
  }
}

export const radioApi = {
  client: api,
  configuredIps: radioDeviceIps,

  async getRadios(): Promise<RadioDevice[]> {
    return Promise.all(radioDeviceIps.map((ip) => getRadioByIp(ip)));
  },

  async updateRadioConfig(ip: string, payload: RadioDeviceConfigPayload): Promise<RadioDevice> {
    await api.put(getDeviceConfigUrl(ip), payload, {
      headers: {
        'Content-Type': 'application/json',
      },
    });
    return getRadioByIp(ip);
  },

  async getChannels(): Promise<Channel[]> {
    return structuredClone(mockChannels);
  },
};
