import axios from 'axios';
import { discoveryBridgeUrl, radioRequestTimeoutMs } from '../config/radioDevices';

export interface RadioDiscovery {
  deviceId: string;
  name: string;
  model: string;
  ip: string;
  httpPort: number;
  lastSeen: string;
  online: boolean;
}

interface RadioDiscoveryEnvelope {
  value?: RadioDiscovery[];
  radios?: RadioDiscovery[];
  Count?: number;
  count?: number;
}

const bridgeApi = axios.create({
  baseURL: discoveryBridgeUrl,
  timeout: radioRequestTimeoutMs,
});

function normalizeRadiosResponse(data: RadioDiscovery[] | RadioDiscoveryEnvelope): RadioDiscovery[] {
  if (Array.isArray(data)) {
    return data;
  }

  if (Array.isArray(data.value)) {
    return data.value;
  }

  if (Array.isArray(data.radios)) {
    return data.radios;
  }

  return [];
}

export const discoveryBridgeApi = {
  async getRadios(): Promise<RadioDiscovery[]> {
    const response = await bridgeApi.get<RadioDiscovery[] | RadioDiscoveryEnvelope>('/api/radios');
    const radios = normalizeRadiosResponse(response.data);
    console.log(`BRIDGE_RADIOS count=${radios.length}`);
    return radios;
  },
};
