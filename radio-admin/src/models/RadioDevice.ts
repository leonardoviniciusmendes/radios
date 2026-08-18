export type RadioStatus = 'ONLINE' | 'OFFLINE';

export interface RadioDeviceApiResponse {
  deviceId: string;
  name: string;
  model: string;
  ip: string;
  mac: string;
  online: boolean;
  channel: string;
  enabled?: boolean;
  appVersion: string;
  wifi: string;
  signal: number;
  battery: number;
}

export interface RadioDeviceConfigPayload {
  name: string;
  channel: string;
  enabled: boolean;
}

export interface RadioDevice {
  id: string;
  name: string;
  deviceId: string;
  model: string;
  ip: string;
  mac: string;
  status: RadioStatus;
  channel: string;
  appVersion: string;
  wifi: string;
  signalQuality: number;
  battery: number;
  lastCommunication: string;
  enabled: boolean;
}
