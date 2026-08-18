import dgram from 'node:dgram';
import cors from 'cors';
import express from 'express';

interface RadioDiscovery {
  deviceId: string;
  name: string;
  model: string;
  ip: string;
  httpPort: number;
  channel: string;
  lastSeen: string;
  lastSeenMs: number;
}

const udpPort = Number(process.env.RADIO_DISCOVERY_UDP_PORT ?? 50006);
const httpPort = Number(process.env.RADIO_DISCOVERY_HTTP_PORT ?? 50100);
const offlineTimeoutMs = 15_000;
const radios = new Map<string, RadioDiscovery>();

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

function getString(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function getNumber(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

const udpServer = dgram.createSocket({ type: 'udp4', reuseAddr: true });

udpServer.on('message', (message, rinfo) => {
  try {
    const payload: unknown = JSON.parse(message.toString('utf8'));
    if (!isRecord(payload)) {
      return;
    }

    const deviceId = getString(payload.deviceId);
    if (!deviceId) {
      return;
    }

    const now = Date.now();
    const name = getString(payload.name) || getString(payload.nome) || `Radio ${deviceId}`;
    const model = getString(payload.model) || getString(payload.modelo) || 'unavailable';
    const ip = getString(payload.ip) || rinfo.address;
    const port = getNumber(payload.httpPort, 50080);
    const channel = getString(payload.channel) || 'Geral';

    radios.set(deviceId, {
      deviceId,
      name,
      model,
      ip,
      httpPort: port,
      channel,
      lastSeen: new Date(now).toISOString(),
      lastSeenMs: now,
    });
  } catch {
    return;
  }
});

udpServer.bind(udpPort, () => {
  console.log(`UDP_DISCOVERY_LISTEN port=${udpPort}`);
});

const app = express();
app.use(cors());

app.get('/api/radios', (_request, response) => {
  const now = Date.now();
  response.json(
    Array.from(radios.values()).map((radio) => ({
      deviceId: radio.deviceId,
      name: radio.name,
      model: radio.model,
      ip: radio.ip,
      httpPort: radio.httpPort,
      channel: radio.channel,
      lastSeen: radio.lastSeen,
      online: now - radio.lastSeenMs <= offlineTimeoutMs,
    })),
  );
});

app.listen(httpPort, () => {
  console.log(`HTTP_BRIDGE_LISTEN port=${httpPort}`);
});
