#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { appendFile, readFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';
const execFileAsync = promisify(execFile);

function parseOpenClawArgs(argv) {
  let prompt = '', sessionId = `species-${Date.now()}`, agent = 'main', local = false;
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if ((a === '-m' || a === '--message') && argv[i + 1] != null) prompt = argv[++i];
    else if (a === '--session-id' && argv[i + 1] != null) sessionId = argv[++i];
    else if (a === '--agent' && argv[i + 1] != null) agent = argv[++i];
    else if (a === '--local') local = true;
  }
  if (!prompt) throw new Error('Species runner expected OpenClaw-style -m <prompt>.');
  return { prompt, sessionId, agent, local };
}

async function readJson(path, fallback = null) {
  try { return JSON.parse(await readFile(path, 'utf8')); } catch { return fallback; }
}

function stageFromSession(id) {
  const arch = id.match(/-arch-([a-z0-9-]+)$/i);
  if (arch) return `arch:${arch[1]}`;
  const council = id.match(/-council-([a-z0-9-]+)$/i);
  if (council) return `council:${council[1]}`;
  if (id.includes('archetype-router')) return 'archetype-router';
  const exact = ['meta-judge','verifier','mutator','critic','synth','judge','builder','skeptic','systems','empirist','empiricist','black-swan','minimalist'];
  for (const stage of exact) if (id.endsWith(`-${stage}`)) return stage === 'empirist' ? 'empiricist' : stage;
  if (id.includes('forge-evolve')) return 'evolution';
  if (id.includes('predator')) return 'predator';
  return 'default';
}

function hash(text) {
  let h = 2166136261;
  for (const ch of String(text)) { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619); }
  return h >>> 0;
}

function speciesAvailable(species) {
  if (!species || species.enabled === false) return false;
  for (const name of species.requiresEnv || []) if (!process.env[name]) return false;
  if (species.type === 'command' && !species.bin && species.binEnv && !process.env[species.binEnv]) return false;
  return true;
}

function chooseSpecies(config, stage, sessionId) {
  const enabled = new Map((config.species || []).filter(speciesAvailable).map(s => [s.id, s]));
  if (!enabled.size) throw new Error('No configured species are currently available.');

  const forcedIds = String(process.env.FORGE_FORCE_SPECIES || '').split(',').map(x => x.trim()).filter(Boolean);
  for (const id of forcedIds) if (enabled.has(id)) return enabled.get(id);

  const raw = config.routes?.[stage] ?? config.routes?.default ?? [config.fallbackSpecies];
  const route = (Array.isArray(raw) ? raw : [raw]).filter(id => enabled.has(id));
  const fallback = enabled.get(config.fallbackSpecies) || [...enabled.values()][0];
  if (!route.length) return fallback;
  if (config.routeMode === 'priority') return enabled.get(route[0]) || fallback;
  return enabled.get(route[hash(`${stage}:${sessionId}`) % route.length]) || fallback;
}

function envJson(name, fallback) {
  if (!name || !process.env[name]) return fallback;
  try { const parsed = JSON.parse(process.env[name]); return Array.isArray(parsed) ? parsed : fallback; }
  catch { return fallback; }
}

function fill(value, ctx) {
  return String(value)
    .replaceAll('{prompt}', ctx.prompt)
    .replaceAll('{sessionId}', ctx.sessionId)
    .replaceAll('{agent}', ctx.agent)
    .replaceAll('{stage}', ctx.stage);
}

function calibrationHint(calibration) {
  if (!calibration || !Number.isFinite(Number(calibration.sampleCount)) || calibration.sampleCount < 3) return '';
  const bias = Number(calibration.bias || 0);
  const mae = Number(calibration.mae || 0);
  const direction = bias > 0.75 ? 'Historical confidence has been too high. Demand stronger evidence before high confidence.'
    : bias < -0.75 ? 'Historical confidence has been too low. Do not understate conclusions that survive verification.'
    : 'Historical confidence is approximately centered.';
  return `\n\n[FORGE CALIBRATION MEMORY]\nSamples=${calibration.sampleCount}; mean absolute calibration error=${mae.toFixed(2)}/10; bias=${bias.toFixed(2)}. ${direction}`;
}

async function invoke(species, ctx) {
  const timeout = Math.max(1000, Math.min(900000, Number(species.timeoutMs || 180000)));
  const bin = (species.binEnv && process.env[species.binEnv]) || species.bin || (species.type === 'openclaw' ? (process.env.FORGE_SPECIES_BASE_BIN || 'openclaw') : null);
  if (!bin) throw new Error(`Species ${species.id} has no executable. Set ${species.binEnv || 'bin'}.`);
  let args;
  if (species.type === 'openclaw') {
    args = ['agent', '--agent', species.agent || ctx.agent || 'main'];
    if (species.local ?? ctx.local) args.push('--local');
    args.push('-m', ctx.prompt, '--session-id', ctx.sessionId);
  } else if (species.type === 'command') {
    const template = envJson(species.argsEnv, species.args || ['{prompt}']);
    args = template.map(x => fill(x, ctx));
  } else {
    throw new Error(`Unknown species type: ${species.type}`);
  }
  const started = Date.now();
  const { stdout, stderr } = await execFileAsync(bin, args, {
    timeout,
    maxBuffer: 16 * 1024 * 1024,
    env: { ...process.env, ...(species.env || {}) }
  });
  const output = stdout.trim() || stderr.trim();
  return { output, durationMs: Date.now() - started };
}

async function main() {
  const parsed = parseOpenClawArgs(process.argv.slice(2));
  const root = process.env.FORGE_HOME || join(homedir(), '.forge-hydra');
  const configPath = resolve(process.env.FORGE_SPECIES_CONFIG || join(process.cwd(), 'config', 'forge-species.json'));
  const activePath = join(root, 'species', 'active.json');
  const explicit = Boolean(process.env.FORGE_SPECIES_CONFIG);
  const config = explicit ? await readJson(configPath, null) : (await readJson(activePath, null) || await readJson(configPath, null));
  if (!config) throw new Error(`Species config not found: ${configPath}`);
  const stage = stageFromSession(parsed.sessionId);
  const calibration = await readJson(join(root, 'calibration.json'), null);
  const prompt = ['judge','meta-judge','verifier'].includes(stage) ? parsed.prompt + calibrationHint(calibration) : parsed.prompt;
  const ctx = { ...parsed, prompt, stage };
  const chosen = chooseSpecies(config, stage, parsed.sessionId);
  const available = (config.species || []).filter(speciesAvailable);
  const fallback = available.find(s => s.id === config.fallbackSpecies) || available[0];
  let result, error = null, used = chosen;
  try {
    result = await invoke(chosen, ctx);
  } catch (e) {
    error = e;
    if (!fallback || fallback.id === chosen.id) throw e;
    used = fallback;
    result = await invoke(fallback, ctx);
  }
  await appendFile(join(root, 'species-runs.jsonl'), JSON.stringify({
    at: new Date().toISOString(), sessionId: parsed.sessionId, stage, requestedSpecies: chosen.id,
    usedSpecies: used.id, fallback: Boolean(error), durationMs: result.durationMs, ok: true
  }) + '\n', 'utf8').catch(() => {});
  process.stdout.write(result.output + (result.output.endsWith('\n') ? '' : '\n'));
}

main().catch(async error => {
  const root = process.env.FORGE_HOME || join(homedir(), '.forge-hydra');
  await appendFile(join(root, 'species-runs.jsonl'), JSON.stringify({at:new Date().toISOString(),ok:false,error:String(error?.message||error)})+'\n','utf8').catch(()=>{});
  console.error(`FORGE species runner failed: ${error?.message || error}`);
  process.exitCode = 1;
});
