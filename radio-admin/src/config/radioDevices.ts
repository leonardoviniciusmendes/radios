const defaultRadioDeviceIps = ['192.168.0.88', '192.168.0.89'];

function parseConfiguredIps(value: string | undefined): string[] {
  if (!value) {
    return defaultRadioDeviceIps;
  }

  const ips = value
    .split(',')
    .map((ip) => ip.trim())
    .filter(Boolean);

  return ips.length > 0 ? ips : defaultRadioDeviceIps;
}

export const radioDeviceIps = parseConfiguredIps(import.meta.env.VITE_RADIO_DEVICE_IPS);
export const radioHttpPort = Number(import.meta.env.VITE_RADIO_HTTP_PORT ?? 50080);
export const radioPollingIntervalMs = 5000;
export const radioRequestTimeoutMs = 1500;
