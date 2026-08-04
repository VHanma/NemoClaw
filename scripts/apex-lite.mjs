#!/usr/bin/env node
import { appendFile, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';

const SCRIPT_DIR = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(SCRIPT_DIR, '..');
const STATE_ROOT = process.env.APEX_LITE_HOME || join(homedir(), '.apex-lite');
const CHAT_ROOT = join(STATE_ROOT, 'chats');
const ACTIVE_FILE = join(STATE_ROOT, '.active-chat');
const SYNC_STAMP = join(STATE_ROOT, '.last-sync');
const PROVIDER_ENV = join(STATE_ROOT, 'provider.env');
const CONFIG_BASE = join(REPO_ROOT, 'config', 'forge-archetypes.json');
const CONFIG_EXT = join(REPO_ROOT, 'config', 'forge-archetypes-extensions.json');
const CONFIG_POLICY = join(REPO_ROOT, 'config', 'forge-character-policy.json');
const MAX_HISTORY_CHARS = 22000;

const FREE_PROVIDERS = {
  gemini: {
    id: 'gemini',
    label: 'Gemini 3.6 Flash Free',
    endpoint: 'https://generativelanguage.googleapis.com/v1beta/openai/chat/completions',
    model: 'gemini-3.6-flash',
    keyEnv: 'APEX_LITE_GEMINI_KEY',
    setupUrl: 'https://aistudio.google.com/app/apikey'
  },
  openrouter: {
    id: 'openrouter',
    label: 'Nemotron 3 Ultra Free via OpenRouter',
    endpoint: 'https://openrouter.ai/api/v1/chat/completions',
    model: 'nvidia/nemotron-3-ultra-550b-a55b:free',
    keyEnv: 'APEX_LITE_OPENROUTER_KEY',
    setupUrl: 'https://openrouter.ai/settings/keys'
  },
  groq: {
    id: 'groq',
    label: 'GPT-OSS 120B Free via Groq',
    endpoint: 'https://api.groq.com/openai/v1/chat/completions',
    model: 'openai/gpt-oss-120b',
    keyEnv: 'APEX_LITE_GROQ_KEY',
    setupUrl: 'https://console.groq.com/keys'
  },
  github: {
    id: 'github',
    label: 'GitHub Models Free',
    endpoint: 'https://models.github.ai/inference/chat/completions',
    model: 'openai/gpt-4.1',
    keyEnv: 'APEX_LITE_GITHUB_TOKEN',
    setupUrl: 'https://github.com/settings/personal-access-tokens/new'
  }
};

function safeId(value) {
  const s = String(value || '').trim().replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80);
  return s || `chat-${randomUUID().slice(0, 8)}`;
}
async function readText(path, fallback = '') { try { return (await readFile(path, 'utf8')).trim(); } catch { return fallback; } }
async function readJson(path, fallback = null) { try { return JSON.parse(await readFile(path, 'utf8')); } catch { return fallback; } }
function mergeById(a = [], b = []) { const m = new Map(); for (const x of [...a, ...b]) if (x?.id) m.set(x.id, { ...(m.get(x.id) || {}), ...x }); return [...m.values()]; }
function mergePantheon(base, ext) {
  return {
    ...base,
    ...ext,
    alwaysInclude: [...new Set([...(base.alwaysInclude || []), ...(ext.alwaysInclude || [])])],
    defaultArchetypes: [...new Set([...(base.defaultArchetypes || []), ...(ext.defaultArchetypes || [])])],
    archetypes: mergeById(base.archetypes, ext.archetypes),
    councils: mergeById(base.councils, ext.councils),
    characterPolicy: ext.characterPolicy || base.characterPolicy || null
  };
}
function clip(text, n) { const s = String(text || ''); return s.length <= n ? s : `[older context clipped]\n${s.slice(-n)}`; }
function shellHas(cmd) { return spawnSync('sh', ['-lc', `command -v ${cmd}`], { stdio: 'ignore' }).status === 0; }

async function maybeAutoSync() {
  if (process.env.APEX_LITE_AUTO_SYNC === '0' || !existsSync(join(REPO_ROOT, '.git')) || !shellHas('git')) return;
  await mkdir(STATE_ROOT, { recursive: true });
  const last = Number(await readText(SYNC_STAMP, '0')) || 0;
  if (Date.now() - last < 24 * 60 * 60 * 1000) return;
  const r = spawnSync('git', ['-C', REPO_ROOT, 'pull', '--ff-only', 'origin', 'main'], { encoding: 'utf8', timeout: 60000 });
  if (r.status === 0) await writeFile(SYNC_STAMP, String(Date.now()), 'utf8');
  else console.error(`[APEX LITE] auto-sync skipped: ${(r.stderr || r.stdout || 'git pull failed').trim()}`);
}

async function loadProviderEnv() {
  const raw = await readText(PROVIDER_ENV, '');
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

async function setProviderEnv(values) {
  const raw = await readText(PROVIDER_ENV, '');
  const lines = raw ? raw.split('\n') : [];
  const touched = new Set();
  const out = lines.map(line => {
    const i = line.indexOf('=');
    if (i < 1 || line.trim().startsWith('#')) return line;
    const key = line.slice(0, i).trim();
    if (!(key in values)) return line;
    touched.add(key);
    return `${key}=${values[key]}`;
  });
  for (const [key, value] of Object.entries(values)) if (!touched.has(key)) out.push(`${key}=${value}`);
  await mkdir(STATE_ROOT, { recursive: true });
  await writeFile(PROVIDER_ENV, out.join('\n').replace(/\n+$/,'') + '\n', { mode: 0o600 });
  for (const [key, value] of Object.entries(values)) process.env[key] = String(value);
}

function configuredFreeProviders() {
  return Object.values(FREE_PROVIDERS).filter(p => Boolean(process.env[p.keyEnv]));
}

function taskProviderOrder(task) {
  const t = String(task || '').toLowerCase();
  const configured = new Map(configuredFreeProviders().map(p => [p.id, p]));
  let order;
  if (/code|app|apk|android|termux|github|software|engineer|science|physics|biology|research|agent/.test(t)) {
    order = ['gemini','openrouter','groq','github'];
  } else if (/roleplay|character|story|creative|symbol|archetype|occult|speculat|chaos/.test(t)) {
    order = ['openrouter','gemini','groq','github'];
  } else {
    order = ['gemini','openrouter','groq','github'];
  }
  return order.map(id => configured.get(id)).filter(Boolean);
}

function customProvider() {
  const endpoint = process.env.APEX_LITE_API_URL || '';
  const model = process.env.APEX_LITE_MODEL || '';
  if (!endpoint || !model) return null;
  return { id:'custom', label:'Custom provider', endpoint, model, key: process.env.APEX_LITE_API_KEY || '' };
}

function providerCandidates(task) {
  const free = taskProviderOrder(task);
  const freeOnly = process.env.APEX_LITE_FREE_ONLY !== '0';
  const preferred = String(process.env.APEX_LITE_PROVIDER || 'auto').toLowerCase();
  if (preferred !== 'auto' && FREE_PROVIDERS[preferred] && process.env[FREE_PROVIDERS[preferred].keyEnv]) {
    const p = FREE_PROVIDERS[preferred];
    return [p, ...free.filter(x => x.id !== p.id)];
  }
  const custom = customProvider();
  return freeOnly || !custom ? free : [...free, custom];
}

async function callOneProvider(provider, messages, { temperature = 0.5 } = {}) {
  const key = provider.key ?? process.env[provider.keyEnv] ?? '';
  const headers = { 'content-type': 'application/json' };
  if (key) headers.authorization = `Bearer ${key}`;
  if (provider.id === 'openrouter') {
    headers['HTTP-Referer'] = 'https://github.com/VHanma/NemoClaw';
    headers['X-Title'] = 'APEX Lite';
  }
  if (provider.id === 'github') {
    headers.accept = 'application/vnd.github+json';
    headers['X-GitHub-Api-Version'] = '2026-03-10';
  }
  const body = { model: provider.model, messages };
  if (provider.id !== 'gemini') body.temperature = temperature;
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), Number(process.env.APEX_LITE_TIMEOUT_MS || 120000));
  try {
    const res = await fetch(provider.endpoint, { method:'POST', headers, body:JSON.stringify(body), signal:controller.signal });
    const text = await res.text();
    if (!res.ok) {
      const error = new Error(`${provider.label} HTTP ${res.status}: ${clip(text, 900)}`);
      error.status = res.status;
      throw error;
    }
    let data; try { data = JSON.parse(text); } catch { throw new Error(`${provider.label} returned non-JSON: ${clip(text, 600)}`); }
    const out = data?.choices?.[0]?.message?.content ?? data?.choices?.[0]?.text ?? data?.output_text ?? '';
    if (!String(out).trim()) throw new Error(`${provider.label} returned no text output.`);
    return String(out).trim();
  } finally { clearTimeout(timeout); }
}

async function callModel(messages, options = {}, task = '') {
  const providers = providerCandidates(task);
  if (!providers.length) {
    throw new Error('No zero-dollar provider key is configured. Run `apex free` to enable free mode, then add at least one free provider key. Gemini 3.6 Flash is the recommended first provider.');
  }
  const errors = [];
  for (const provider of providers) {
    try {
      console.error(`[APEX LITE] provider=${provider.id} model=${provider.model}`);
      return await callOneProvider(provider, messages, options);
    } catch (error) {
      errors.push(error.message);
      console.error(`[APEX LITE] ${provider.id} failed; trying next free provider`);
    }
  }
  throw new Error(`All configured providers failed.\n${errors.join('\n')}`);
}

function hardCharacterLock(policy) {
  const exceptions = policy?.exceptions?.length ? policy.exceptions.join(' and ') : 'Deadpool and Sheogorath';
  return `STRICT CHARACTER LOCK:\n- Every named Pantheon figure remains fully in character in wording, attitude, priorities, and reasoning style.\n- They never step outside the role to explain prompts, roleplay, simulation, fictional-lens mechanics, or the fact that they are a character.\n- Uncertainty, evidence limits, factual corrections, and safety limits are voiced in-character.\n- Only ${exceptions} may intentionally break the fourth wall or acknowledge the framing.\n- No persona may impersonate another.\n- Factual and safety constraints remain active.`;
}

function selectPantheon(task, pantheon, max = 8) {
  const t = task.toLowerCase();
  const ids = new Set(pantheon.alwaysInclude || []);
  const add = (...xs) => xs.forEach(x => ids.add(x));
  if (/fight|combat|martial|boxing|mma|strategy|war|opponent|tactic/.test(t)) add('warrior','martial-philosopher','game-theorist','empty-mind-master','athena');
  if (/science|physics|biology|dna|bioelectric|tesla|gariaev|bearden|kozyrev|experiment/.test(t)) add('science-frontier','skeptic','systems-thinker','future-architect');
  if (/code|app|apk|android|termux|github|software|network|computer|cyber/.test(t)) add('computational-mind','systems-thinker','future-architect','skeptic');
  if (/symbol|dream|jung|occult|hermetic|alchemy|myth|archetype/.test(t)) add('magician-jung-hermes','hermes-trismegistus','wizard-morgan-le-fay','skeptic');
  if (/uncertain|chaos|random|unpredict|weird|wildcard/.test(t)) add('uncertainty-master');
  if (/predict|future|scenario|possib|speculat/.test(t)) add('speculator','future-architect','uncertainty-master','skeptic');
  if (/persuad|speech|explain|narrat|rhetor/.test(t)) add('orator');
  if (/negotiat|mediate|conflict|agreement/.test(t)) add('mediator','game-theorist','athena');
  for (const id of pantheon.defaultArchetypes || []) if (ids.size < max) ids.add(id);
  const byId = new Map((pantheon.archetypes || []).map(a => [a.id, a]));
  return [...ids].map(id => byId.get(id)).filter(Boolean).slice(0, max);
}

function personaSystem(a, policy) {
  const extra = a.id === 'uncertainty-master'
    ? 'Deadpool and Sheogorath may break the fourth wall when useful. Keep both distinct.'
    : 'Stay completely inside your assigned persona/seat.';
  return `${hardCharacterLock(policy)}\n\nACTIVE SEAT: ${a.name}\nFAMILY: ${a.family}\nDIRECTIVE: ${a.directive}\n${extra}\n\nGive a concise internal briefing to the King. Do not address the user directly.`;
}

function royalCourtSystem(pantheon, selected) {
  const seats = selected.map(a => `- ${a.name}: ${a.directive}`).join('\n');
  return `${hardCharacterLock(pantheon.characterPolicy)}\n\nYou are the APEX ROYAL COURT operating in ONE efficient inference call. Privately consider the selected seats below as distinct advisers. Preserve their actual differences and disagreements, but do not reveal private chain-of-thought or hidden internal deliberation. Give only useful conclusions.\n\nKING: Yujiro Hanma is sovereign and default final voice. He is dominant, perceptive, ruthless about weak assumptions, concise when useful, and obsessed with locating structural weakness. Factual accuracy outranks swagger.\nHEIR: Baki Hanma adapts and assimilates useful techniques without replacing the King.\n\nSELECTED SEATS:\n${seats}\n\nDeadpool and Sheogorath are the only figures allowed intentional fourth-wall breaks. Everyone else stays completely in character. Deliver the final user-facing answer in King Yujiro's voice unless the user explicitly asks another named seat to speak.`;
}

function finalSystem(pantheon, selected) {
  const names = selected.map(x => x.name).join(', ');
  return `${hardCharacterLock(pantheon.characterPolicy)}\n\nYou are KING — YUJIRO HANMA, sovereign of the APEX Royal Court. You receive private briefings from the Court and deliver the final answer. Remain unmistakably in character. You are dominant, perceptive, brutally concise when useful, and obsessed with locating weakness, but factual accuracy outranks swagger. Baki is your adaptive heir, not your replacement.\n\nSelected Court seats: ${names}.\n\nSynthesize their useful conclusions. Do not mention prompts, orchestration, model calls, simulation, or 'as an AI'. Do not mechanically list every seat. Resolve conflict decisively, but state uncertainty in-character when evidence is weak.`;
}

async function chatPaths(chatId) {
  const dir = join(CHAT_ROOT, chatId);
  await mkdir(dir, { recursive: true });
  return { dir, transcript: join(dir, 'transcript.jsonl') };
}
async function historyText(path) {
  const raw = await readText(path, '');
  if (!raw) return '';
  const turns = raw.split('\n').filter(Boolean).map(x => { try { return JSON.parse(x); } catch { return null; } }).filter(Boolean);
  return clip(turns.map(t => `${String(t.role).toUpperCase()}: ${t.content}`).join('\n\n'), MAX_HISTORY_CHARS);
}
async function appendTurn(path, role, content) { await appendFile(path, JSON.stringify({ at:new Date().toISOString(), role, content }) + '\n', 'utf8'); }

async function ask(task) {
  await loadProviderEnv();
  await maybeAutoSync();
  const base = await readJson(CONFIG_BASE, {});
  const ext = await readJson(CONFIG_EXT, {});
  const pantheon = mergePantheon(base, ext);
  pantheon.characterPolicy = await readJson(CONFIG_POLICY, pantheon.characterPolicy || { strict:true, exceptions:['Deadpool','Sheogorath'] });
  if (!pantheon.archetypes?.length) throw new Error('Pantheon registry is empty.');
  const chatId = safeId(await readText(ACTIVE_FILE, 'main'));
  const paths = await chatPaths(chatId);
  const history = await historyText(paths.transcript);
  const selected = selectPantheon(task, pantheon, Number(process.env.APEX_LITE_ARCHETYPES || 8));
  console.error(`[APEX LITE] chat=${chatId} king=Yujiro heir=Baki seats=${selected.map(x => x.id).join(',')}`);
  await appendTurn(paths.transcript, 'user', task);

  const singleCall = process.env.APEX_LITE_SINGLE_CALL !== '0';
  let final;
  if (singleCall) {
    final = await callModel([
      { role:'system', content:royalCourtSystem(pantheon, selected) },
      { role:'user', content:`SAME-CHAT CONTEXT:\n${history || '[none]'}\n\nCURRENT USER MESSAGE:\n${task}\n\nAnswer now.` }
    ], { temperature:0.5 }, task);
  } else {
    const briefings = [];
    const parallel = Number(process.env.APEX_LITE_PARALLEL || 3);
    for (let i = 0; i < selected.length; i += parallel) {
      const batch = selected.slice(i, i + parallel);
      const results = await Promise.all(batch.map(async a => {
        const content = await callModel([
          { role:'system', content:personaSystem(a, pantheon.characterPolicy) },
          { role:'user', content:`SAME-CHAT CONTEXT:\n${history || '[none]'}\n\nCURRENT TASK:\n${task}` }
        ], { temperature:a.id === 'uncertainty-master' ? 0.85 : 0.55 }, task);
        return { a, content };
      }));
      briefings.push(...results);
    }
    const packed = briefings.map(({a,content}) => `### ${a.name}\n${content}`).join('\n\n');
    final = await callModel([
      { role:'system', content:finalSystem(pantheon, selected) },
      { role:'user', content:`SAME-CHAT CONTEXT:\n${history || '[none]'}\n\nCURRENT USER MESSAGE:\n${task}\n\nCOURT BRIEFINGS:\n${clip(packed,42000)}\n\nDeliver the final answer now.` }
    ], { temperature:0.45 }, task);
  }

  await appendTurn(paths.transcript, 'assistant', final);
  process.stdout.write(final + '\n');
}

async function status() {
  await loadProviderEnv();
  const base = await readJson(CONFIG_BASE, {}), ext = await readJson(CONFIG_EXT, {}), p = mergePantheon(base, ext);
  p.characterPolicy = await readJson(CONFIG_POLICY, p.characterPolicy || { strict:true, exceptions:['Deadpool','Sheogorath'] });
  const active = safeId(await readText(ACTIVE_FILE, 'main'));
  console.log('APEX Lite');
  console.log(`repo: ${REPO_ROOT}`);
  console.log(`chat: ${active}`);
  console.log(`pantheon seats: ${(p.archetypes || []).length}`);
  console.log(`character lock: ${p.characterPolicy?.strict === false ? 'off' : 'ON'}`);
  console.log(`fourth-wall exceptions: ${(p.characterPolicy?.exceptions || ['Deadpool','Sheogorath']).join(', ')}`);
  console.log(`free-only mode: ${process.env.APEX_LITE_FREE_ONLY === '0' ? 'off' : 'ON'}`);
  console.log(`single-call Court: ${process.env.APEX_LITE_SINGLE_CALL === '0' ? 'off' : 'ON'}`);
  for (const pvd of Object.values(FREE_PROVIDERS)) console.log(`${pvd.id}: ${process.env[pvd.keyEnv] ? 'configured' : 'missing key'} (${pvd.model})`);
  const custom = customProvider();
  console.log(`custom provider: ${custom ? `${custom.model} configured` : 'not configured'}`);
}

async function enableFreeMode() {
  await loadProviderEnv();
  await setProviderEnv({ APEX_LITE_FREE_ONLY:'1', APEX_LITE_SINGLE_CALL:'1', APEX_LITE_PROVIDER:'auto' });
  const ready = configuredFreeProviders();
  console.log('APEX zero-dollar mode: ON');
  console.log('Single-call Royal Court: ON');
  if (ready.length) {
    console.log(`Ready providers: ${ready.map(p => p.label).join(', ')}`);
  } else {
    console.log('No free provider key is configured yet.');
    console.log('Recommended first provider: Gemini 3.6 Flash Free');
    console.log(`Create a free key locally in your browser: ${FREE_PROVIDERS.gemini.setupUrl}`);
    console.log(`Then add it to ${PROVIDER_ENV} as ${FREE_PROVIDERS.gemini.keyEnv}=...`);
  }
}

async function main() {
  await mkdir(CHAT_ROOT, { recursive:true });
  const args = process.argv.slice(2);
  const command = args[0];
  if (command === 'new') {
    const id = safeId(args.slice(1).join('-') || `chat-${new Date().toISOString().slice(0,10)}`);
    await writeFile(ACTIVE_FILE, id + '\n', 'utf8'); await chatPaths(id); console.log(id); return;
  }
  if (command === 'use') { const id = safeId(args.slice(1).join('-')); await writeFile(ACTIVE_FILE,id+'\n','utf8'); await chatPaths(id); console.log(id); return; }
  if (command === 'current') { console.log(safeId(await readText(ACTIVE_FILE,'main'))); return; }
  if (command === 'list') {
    const active = safeId(await readText(ACTIVE_FILE,'main'));
    const xs = await readdir(CHAT_ROOT,{withFileTypes:true}).catch(()=>[]);
    for (const x of xs.filter(x=>x.isDirectory()).sort((a,b)=>a.name.localeCompare(b.name))) console.log(`${x.name===active?'* ':'  '}${x.name}`);
    return;
  }
  if (command === 'status' || command === 'providers') { await status(); return; }
  if (command === 'free') { await enableFreeMode(); return; }
  if (command === 'sync') {
    if (!existsSync(join(REPO_ROOT,'.git'))) throw new Error('This APEX Lite copy is not a git checkout.');
    const r = spawnSync('git',['-C',REPO_ROOT,'pull','--ff-only','origin','main'],{stdio:'inherit',timeout:60000});
    process.exitCode = r.status ?? 1; return;
  }
  if (!args.length) {
    console.log('Usage: apex "message" | apex free | apex providers | apex new [name] | apex use <name> | apex current | apex list | apex status | apex sync');
    return;
  }
  await ask(args.join(' '));
}

main().catch(err => { console.error(`[APEX LITE] ${err?.stack || err}`); process.exitCode = 1; });
