#!/usr/bin/env node
import { readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

const HOME = process.env.APEX_LITE_HOME || join(homedir(), '.apex-lite');
const ENV_FILE = join(HOME, 'provider.env');

const providers = [
  {
    id: 'gemini',
    label: 'Gemini 3.6 Flash Free',
    keyEnv: 'APEX_LITE_GEMINI_KEY',
    endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
    model: 'gemini-3.6-flash'
  },
  {
    id: 'openrouter',
    label: 'Nemotron 3 Ultra Free via OpenRouter',
    keyEnv: 'APEX_LITE_OPENROUTER_KEY',
    endpoint: 'https://openrouter.ai/api/v1/chat/completions',
    model: 'nvidia/nemotron-3-ultra-550b-a55b:free'
  },
  {
    id: 'groq',
    label: 'GPT-OSS 120B Free via Groq',
    keyEnv: 'APEX_LITE_GROQ_KEY',
    endpoint: 'https://api.groq.com/openai/v1/chat/completions',
    model: 'openai/gpt-oss-120b'
  },
  {
    id: 'github',
    label: 'GitHub Models Free',
    keyEnv: 'APEX_LITE_GITHUB_TOKEN',
    endpoint: 'https://models.github.ai/inference/chat/completions',
    model: 'openai/gpt-4.1'
  }
];

function cleanError(data, text) {
  const candidates = [
    data?.error?.message,
    data?.message,
    data?.error?.status,
    data?.detail,
    data?.errors?.[0]?.message
  ].filter(Boolean);
  let msg = candidates.length ? String(candidates[0]) : String(text || '').replace(/\s+/g, ' ').trim();
  msg = msg.replace(/AIza[0-9A-Za-z_-]{20,}/g, '[REDACTED_KEY]')
           .replace(/sk-[A-Za-z0-9_-]{12,}/g, '[REDACTED_KEY]')
           .replace(/gh[pousr]_[A-Za-z0-9_]{12,}/g, '[REDACTED_TOKEN]');
  return msg.slice(0, 500) || 'No error message returned.';
}

async function loadEnv() {
  let raw = '';
  try { raw = await readFile(ENV_FILE, 'utf8'); }
  catch {
    console.error(`provider.env is missing: ${ENV_FILE}`);
    process.exit(2);
  }
  for (const line of raw.split('\n')) {
    const s = line.trim();
    if (!s || s.startsWith('#')) continue;
    const i = s.indexOf('=');
    if (i < 1) continue;
    const k = s.slice(0, i).trim();
    let v = s.slice(i + 1).trim();
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    if (!(k in process.env)) process.env[k] = v;
  }
}

async function testProvider(p) {
  const key = process.env[p.keyEnv] || '';
  if (!key) {
    console.log(`${p.id}: SKIP, key missing`);
    return { ok: false, skipped: true };
  }

  const headers = {
    'content-type': 'application/json',
    authorization: `Bearer ${key}`
  };
  if (p.id === 'openrouter') {
    headers['HTTP-Referer'] = 'https://github.com/VHanma/NemoClaw';
    headers['X-Title'] = 'APEX Lite Doctor';
  }
  if (p.id === 'github') {
    headers.accept = 'application/vnd.github+json';
    headers['X-GitHub-Api-Version'] = '2022-11-28';
  }

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30000);
  try {
    const res = await fetch(p.endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        model: p.model,
        messages: [{ role: 'user', content: 'Reply with exactly: APEX_OK' }],
        max_tokens: 16
      }),
      signal: controller.signal
    });
    const text = await res.text();
    let data = null;
    try { data = JSON.parse(text); } catch {}

    if (!res.ok) {
      console.log(`${p.id}: FAIL HTTP ${res.status}`);
      console.log(`  ${cleanError(data, text)}`);
      if (res.status === 401 || res.status === 403) console.log('  diagnosis: key rejected, disabled, wrong provider, or API access not enabled');
      else if (res.status === 404) console.log('  diagnosis: model or endpoint unavailable for this account/region');
      else if (res.status === 429) console.log('  diagnosis: free-tier quota or rate limit reached');
      else if (res.status === 400) console.log('  diagnosis: provider rejected the request; message above identifies the field/model issue');
      return { ok: false };
    }

    const out = data?.choices?.[0]?.message?.content ?? data?.choices?.[0]?.text ?? data?.output_text ?? '';
    console.log(`${p.id}: OK HTTP ${res.status} (${p.model})`);
    if (out) console.log(`  response: ${String(out).replace(/\s+/g, ' ').slice(0, 120)}`);
    return { ok: true };
  } catch (err) {
    const msg = err?.name === 'AbortError' ? 'request timed out after 30 seconds' : String(err?.message || err);
    console.log(`${p.id}: FAIL network`);
    console.log(`  ${msg.slice(0, 500)}`);
    return { ok: false };
  } finally {
    clearTimeout(timer);
  }
}

await loadEnv();
console.log('APEX Lite Provider Doctor');
console.log(`provider file: ${ENV_FILE}`);
console.log('Keys are never printed.\n');

let configured = 0;
let working = 0;
for (const p of providers) {
  if (process.env[p.keyEnv]) configured++;
  const result = await testProvider(p);
  if (result.ok) working++;
}

console.log(`\nConfigured: ${configured} | Working: ${working}`);
if (!configured) {
  console.log('RESULT: no free-provider key is configured.');
  process.exitCode = 2;
} else if (!working) {
  console.log('RESULT: configured provider(s) are failing. Use the FAIL line above as the root cause.');
  process.exitCode = 1;
} else {
  console.log('RESULT: at least one zero-dollar provider is healthy.');
}
