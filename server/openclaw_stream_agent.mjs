#!/usr/bin/env node
// OpenClaw gateway 串流橋接：stdin 不用，argv 帶訊息，stdout 逐行吐 JSON 事件
// {"type":"delta","text":...,"fullText":...} / {"type":"final","text":...} / {"type":"error","message":...}
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import crypto from 'node:crypto';

const SDK_PATH = process.env.OPENCLAW_SDK_PATH
  ?? path.join(os.homedir(), '.npm-global/lib/node_modules/openclaw/dist/plugin-sdk/gateway-runtime.js');
const CONFIG_PATH = process.env.OPENCLAW_CONFIG
  ?? path.join(os.homedir(), '.openclaw/openclaw.json');
const GATEWAY_URL = process.env.OPENCLAW_GATEWAY_URL ?? 'ws://127.0.0.1:18789';

function emit(record) {
  process.stdout.write(`${JSON.stringify(record)}\n`);
}

function fail(message) {
  emit({ type: 'error', message });
  process.exitCode = 1;
}

function readPassword() {
  const raw = fs.readFileSync(CONFIG_PATH, 'utf8');
  const config = JSON.parse(raw);
  const password = config?.gateway?.auth?.password;
  if (!password) throw new Error('gateway password not found');
  return password;
}

const message = process.argv[2] ?? '';
const sessionId = process.argv[3] || '';
const agentId = process.argv[4] || 'main';

if (!message.trim()) {
  fail('missing message');
  process.exit();
}

let client;
let finalText = '';
let settled = false;
let timeoutHandle;

function stopSoon() {
  if (timeoutHandle) clearTimeout(timeoutHandle);
  setTimeout(() => client?.stop(), 50);
}

try {
  const { GatewayClient } = await import(SDK_PATH);
  const password = readPassword();
  client = new GatewayClient({
    url: GATEWAY_URL,
    password,
    instanceId: crypto.randomUUID(),
    clientName: 'cli',
    clientDisplayName: 'shrimp-voice-stream',
    mode: 'cli',
    role: 'operator',
    scopes: ['operator.admin', 'operator.read', 'operator.write', 'operator.approvals', 'operator.pairing'],
    onHelloOk: async () => {
      try {
        const params = {
          message,
          agentId,
          timeout: 90,
          idempotencyKey: crypto.randomUUID()
        };
        if (sessionId) params.sessionId = sessionId;
        const result = await client.request('agent', params, { expectFinal: true, timeoutMs: 120000 });

        const payloads = result?.result?.payloads ?? result?.payloads ?? [];
        const text = payloads[0]?.text ?? result?.text ?? finalText;
        settled = true;
        emit({ type: 'final', text: text ?? finalText });
        stopSoon();
      } catch (error) {
        settled = true;
        emit({ type: 'error', message: error?.message ?? String(error) });
        stopSoon();
      }
    },
    onEvent: (evt) => {
      const payload = evt?.payload;
      if (evt?.event !== 'agent' || payload?.stream !== 'assistant') return;
      const data = payload?.data ?? {};
      const delta = typeof data.delta === 'string' ? data.delta : '';
      const text = typeof data.text === 'string' ? data.text : '';
      if (text) finalText = text;
      if (delta) emit({ type: 'delta', text: delta, fullText: finalText });
    },
    onConnectError: (error) => {
      if (!settled) emit({ type: 'error', message: error?.message ?? String(error) });
    },
    onClose: (code, reason) => {
      if (!settled && code !== 1005) emit({ type: 'error', message: `gateway closed ${code}: ${reason ?? ''}`.trim() });
    }
  });

  client.start();
  timeoutHandle = setTimeout(() => {
    if (!settled) {
      emit({ type: 'error', message: 'gateway request timeout' });
      client.stop();
      process.exitCode = 1;
    }
  }, 125000);
} catch (error) {
  fail(error?.message ?? String(error));
}
