#!/usr/bin/env node
import { execFile } from 'node:child_process';
import { mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { randomUUID } from 'node:crypto';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

function parseArgs(argv) {
  const flags = {};
  for (const arg of argv) {
    if (!arg.startsWith('--')) continue;
    const [key, ...rest] = arg.slice(2).split('=');
    flags[key] = rest.length ? rest.join('=') : true;
  }
  return flags;
}

function asInt(value, fallback, min = 1, max = 100) {
  const n = Number.parseInt(String(value ?? ''), 10);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}

function asNum(value, fallback, min = -Infinity, max = Infinity) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback;
}

function extractJson(text) {
  const trimmed = String(text ?? '').trim();
  const fenced = trimmed.match(/```(?:json)?\s*([\s\S]*?)```/i);
  const candidate = fenced ? fenced[1].trim() : trimmed;
  try { return JSON.parse(candidate); } catch {}
  const first = candidate.indexOf('{');
  const last = candidate.lastIndexOf('}');
  if (first >= 0 && last > first) {
    try { return JSON.parse(candidate.slice(first, last + 1)); } catch {}
  }
  return null;
}

async function readJson(path) {
  return JSON.parse(await readFile(path, 'utf8'));
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function normalizeRubric(rubric) {
  const positive = Object.fromEntries(Object.entries(rubric || {}).map(([k, v]) => [k, Math.max(0.01, Number(v) || 0.01)]));
  const total = Object.values(positive).reduce((a, b) => a + b, 0) || 1;
  return Object.fromEntries(Object.entries(positive).map(([k, v]) => [k, Number((v / total).toFixed(4))]));
}

function hashSeed(text) {
  let h = 2166136261;
  for (const ch of text) {
    h ^= ch.charCodeAt(0);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function rng(seedText) {
  let x = hashSeed(seedText) || 1;
  return () => {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    return (x >>> 0) / 4294967296;
  };
}

const GENE_LIBRARY = [
  'Before committing, explicitly test the answer against every user constraint and remove anything that violates the requested output shape.',
  'Prefer falsifiable reasoning: identify what observation, counterexample, or test would prove the leading answer wrong.',
  'When the task has a machine-checkable result, derive it independently a second way before finalizing.',
  'Separate task solving from answer presentation: solve expansively, then compress to exactly the requested form.',
  'Search for one credible alternative hypothesis before convergence, but do not preserve alternatives that fail the evidence.',
  'Track dependencies and second-order effects; a locally correct step is insufficient if it breaks the larger system.',
  'Use confidence only after checking for missing premises, ambiguous terms, and hidden assumptions.',
  'For code or algorithms, reason through at least one concrete execution trace before claiming correctness.'
];

function deterministicMutation(parent, generation, index) {
  const out = clone(parent);
  const random = rng(`${generation}:${index}:${JSON.stringify(parent.rubric)}`);
  const roleIndex = Math.floor(random() * out.roles.length);
  const gene = GENE_LIBRARY[Math.floor(random() * GENE_LIBRARY.length)];
  out.roles[roleIndex].directive = `${out.roles[roleIndex].directive} ${gene}`;

  const keys = Object.keys(out.rubric || {});
  if (keys.length >= 2) {
    const a = keys[Math.floor(random() * keys.length)];
    let b = keys[Math.floor(random() * keys.length)];
    if (b === a) b = keys[(keys.indexOf(a) + 1) % keys.length];
    const shift = 0.02 + random() * 0.05;
    out.rubric[a] = (Number(out.rubric[a]) || 0) + shift;
    out.rubric[b] = Math.max(0.01, (Number(out.rubric[b]) || 0) - shift);
    out.rubric = normalizeRubric(out.rubric);
  }

  out.version = Number(parent.version || 1) + generation;
  out.evolution = {
    parentVersion: parent.version || 1,
    generation,
    mutation: `role:${out.roles[roleIndex].id}; gene:${gene}`
  };
  return out;
}

async function runOpenClaw(prompt, sessionId) {
  const bin = process.env.FORGE_OPENCLAW_BIN || 'openclaw';
  const agent = process.env.FORGE_AGENT || 'main';
  const local = process.env.FORGE_LOCAL !== '0';
  const args = ['agent', '--agent', agent];
  if (local) args.push('--local');
  args.push('-m', prompt, '--session-id', sessionId);
  const { stdout, stderr } = await execFileAsync(bin, args, {
    timeout: asInt(process.env.FORGE_TIMEOUT_MS, 180000, 1000, 900000),
    maxBuffer: 8 * 1024 * 1024,
    env: process.env
  });
  return stdout.trim() || stderr.trim();
}

function mutationPrompt(parent, trainSummary) {
  return `You are the FORGE evolutionary mutation designer. Improve a cognition configuration using TRAINING results only. You are deliberately denied held-out test details to reduce overfitting.\n\nPARENT CONFIG:\n${JSON.stringify(parent)}\n\nTRAINING SUMMARY:\n${JSON.stringify(trainSummary)}\n\nPropose exactly one conservative mutation. Keep every existing role and every rubric key. You may append one short reasoning rule to one or two role directives and adjust rubric weights slightly. Never delete capabilities. Return JSON ONLY:\n{\n  "roleAppends": [{"roleId":"builder","text":"short reusable reasoning rule"}],\n  "rubricDelta": {"accuracy":0.02,"novelty":-0.02},\n  "reason":"why this should address the training weakness"\n}`;
}

function applyAiMutation(parent, proposal, generation, index) {
  const out = clone(parent);
  const appends = Array.isArray(proposal?.roleAppends) ? proposal.roleAppends.slice(0, 2) : [];
  for (const item of appends) {
    const role = out.roles.find((r) => r.id === item?.roleId);
    if (!role || typeof item?.text !== 'string' || !item.text.trim()) continue;
    role.directive = `${role.directive} ${item.text.trim()}`;
  }
  if (proposal?.rubricDelta && typeof proposal.rubricDelta === 'object') {
    for (const [key, delta] of Object.entries(proposal.rubricDelta)) {
      if (!(key in out.rubric)) continue;
      const d = Math.max(-0.08, Math.min(0.08, Number(delta) || 0));
      out.rubric[key] = Math.max(0.01, Number(out.rubric[key]) + d);
    }
    out.rubric = normalizeRubric(out.rubric);
  }
  out.version = Number(parent.version || 1) + generation;
  out.evolution = {
    parentVersion: parent.version || 1,
    generation,
    mutation: `ai-${index}: ${String(proposal?.reason || 'training-directed mutation').slice(0, 240)}`
  };
  return out;
}

function norm(s) {
  return String(s ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}

function words(s) {
  return String(s ?? '').trim().split(/\s+/).filter(Boolean);
}

function checkOne(answer, check) {
  const raw = String(answer ?? '');
  const normalized = norm(raw);
  const value = String(check.value ?? '');
  switch (check.type) {
    case 'exact': return normalized === norm(value);
    case 'contains': return normalized.includes(norm(value));
    case 'containsAny': return (check.values || []).some((v) => normalized.includes(norm(v)));
    case 'containsAll': return (check.values || []).every((v) => normalized.includes(norm(v)));
    case 'excludes': return !normalized.includes(norm(value));
    case 'regex': {
      try { return new RegExp(value, check.flags || 'i').test(raw); } catch { return false; }
    }
    case 'maxWords': return words(raw).length <= Number(check.value);
    case 'maxChars': return raw.length <= Number(check.value);
    default: return false;
  }
}

function objectiveScore(answer, task) {
  const checks = Array.isArray(task.checks) ? task.checks : [];
  if (!checks.length) return { score: 0, passed: 0, total: 0, details: [] };
  let weightTotal = 0;
  let earned = 0;
  const details = checks.map((check) => {
    const weight = Math.max(0.01, Number(check.weight) || 1);
    const pass = checkOne(answer, check);
    weightTotal += weight;
    if (pass) earned += weight;
    return { type: check.type, pass, weight };
  });
  return {
    score: Number(((earned / weightTotal) * 10).toFixed(3)),
    passed: details.filter((x) => x.pass).length,
    total: details.length,
    details
  };
}

async function runHydra(task, configPath, candidateHome, agents) {
  const script = process.env.FORGE_HYDRA_SCRIPT || resolve(process.cwd(), 'scripts', 'forge-hydra.mjs');
  const args = [script, task.prompt, `--agents=${agents}`];
  const { stdout, stderr } = await execFileAsync(process.execPath, args, {
    timeout: asInt(process.env.FORGE_EVOLVE_TASK_TIMEOUT_MS, 360000, 1000, 900000),
    maxBuffer: 16 * 1024 * 1024,
    env: {
      ...process.env,
      FORGE_CONFIG: configPath,
      FORGE_HOME: candidateHome
    }
  });
  return { answer: stdout.trim(), stderr: stderr.trim() };
}

async function evaluateCandidate(candidate, suite, workRoot, agents) {
  const candidateDir = join(workRoot, candidate.id);
  await mkdir(candidateDir, { recursive: true });
  const configPath = join(candidateDir, 'config.json');
  const statePath = join(candidateDir, 'state');
  await writeFile(configPath, JSON.stringify(candidate.config, null, 2) + '\n');

  const results = [];
  for (const task of suite.tasks) {
    const run = await runHydra(task, configPath, statePath, agents);
    const scored = objectiveScore(run.answer, task);
    results.push({
      id: task.id,
      split: task.split,
      score: scored.score,
      passed: scored.passed,
      total: scored.total,
      details: scored.details,
      answer: run.answer
    });
  }

  const aggregate = (split) => {
    const chosen = results.filter((r) => r.split === split);
    const scores = chosen.map((r) => r.score);
    return {
      mean: scores.length ? scores.reduce((a, b) => a + b, 0) / scores.length : 0,
      worst: scores.length ? Math.min(...scores) : 0,
      count: scores.length
    };
  };

  const train = aggregate('train');
  const holdout = aggregate('holdout');
  const overall = 0.4 * train.mean + 0.6 * holdout.mean;
  return { ...candidate, train, holdout, overall, results };
}

function publicTrainingSummary(evaluated) {
  return {
    candidateId: evaluated.id,
    trainMean: Number(evaluated.train.mean.toFixed(3)),
    trainWorst: Number(evaluated.train.worst.toFixed(3)),
    failures: evaluated.results.filter((r) => r.split === 'train' && r.score < 10).map((r) => ({
      id: r.id,
      score: r.score,
      failedChecks: r.details.filter((d) => !d.pass).map((d) => d.type)
    }))
  };
}

function compactResult(e) {
  return {
    id: e.id,
    label: e.label,
    trainMean: Number(e.train.mean.toFixed(3)),
    trainWorst: Number(e.train.worst.toFixed(3)),
    holdoutMean: Number(e.holdout.mean.toFixed(3)),
    holdoutWorst: Number(e.holdout.worst.toFixed(3)),
    overall: Number(e.overall.toFixed(3)),
    mutation: e.config.evolution?.mutation || 'parent'
  };
}

async function proposePopulation(parent, generation, population, trainSummary) {
  const variants = [];
  for (let i = 0; i < population; i++) {
    if (i === 0 && process.env.FORGE_EVOLVE_AI !== '0') {
      try {
        const raw = await runOpenClaw(mutationPrompt(parent, trainSummary), `forge-evolve-g${generation}-${randomUUID()}`);
        const parsed = extractJson(raw);
        if (parsed) {
          variants.push({ id: `g${generation}-ai`, label: `generation-${generation}-ai`, config: applyAiMutation(parent, parsed, generation, i) });
          continue;
        }
      } catch (error) {
        console.error(`[EVOLVE] AI mutation unavailable: ${error.message}`);
      }
    }
    variants.push({ id: `g${generation}-m${i}`, label: `generation-${generation}-mutation-${i}`, config: deterministicMutation(parent, generation, i) });
  }
  return variants;
}

async function main() {
  const flags = parseArgs(process.argv.slice(2));
  const generations = asInt(flags.generations, 1, 1, 20);
  const population = asInt(flags.population, 4, 2, 20);
  const minDelta = asNum(flags['min-delta'], 0.15, 0, 5);
  const worstDrop = asNum(flags['max-worst-drop'], 0.5, 0, 5);
  const agents = asInt(flags.agents, 6, 2, 20);
  const configPath = resolve(String(flags.config || process.env.FORGE_CONFIG || join(process.cwd(), 'config', 'forge-hydra.json')));
  const suitePath = resolve(String(flags.suite || join(process.cwd(), 'config', 'forge-benchmarks.json')));
  const parentInitial = await readJson(configPath);
  const suite = await readJson(suitePath);
  if (!Array.isArray(parentInitial.roles) || !parentInitial.roles.length) throw new Error('Invalid parent config: roles missing.');
  if (!Array.isArray(suite.tasks) || !suite.tasks.some((t) => t.split === 'train') || !suite.tasks.some((t) => t.split === 'holdout')) {
    throw new Error('Benchmark suite must contain train and holdout tasks.');
  }

  const root = process.env.FORGE_HOME || join(homedir(), '.forge-hydra');
  const evolutionHome = join(root, 'evolution');
  await mkdir(evolutionHome, { recursive: true });
  const experimentId = `${new Date().toISOString().replace(/[:.]/g, '-')}-${randomUUID().slice(0, 8)}`;
  const workRoot = join(evolutionHome, 'experiments', experimentId);
  await mkdir(workRoot, { recursive: true });

  let champion = { id: 'parent', label: 'parent', config: clone(parentInitial) };
  let championEval = await evaluateCandidate(champion, suite, workRoot, agents);
  const history = [{ generation: 0, candidates: [compactResult(championEval)], promoted: 'parent' }];
  console.error(`[EVOLVE] parent overall=${championEval.overall.toFixed(3)} holdout=${championEval.holdout.mean.toFixed(3)}`);

  for (let generation = 1; generation <= generations; generation++) {
    const trainSummary = publicTrainingSummary(championEval);
    const variants = await proposePopulation(champion.config, generation, population, trainSummary);
    const evaluated = [];
    for (const variant of variants) {
      console.error(`[EVOLVE] evaluating ${variant.id}...`);
      evaluated.push(await evaluateCandidate(variant, suite, workRoot, agents));
    }

    evaluated.sort((a, b) => b.overall - a.overall || b.holdout.worst - a.holdout.worst);
    const best = evaluated[0];
    const beatsParent = best.overall >= championEval.overall + minDelta;
    const protectsWorstCase = best.holdout.worst >= championEval.holdout.worst - worstDrop;
    const promoted = beatsParent && protectsWorstCase;

    history.push({
      generation,
      candidates: evaluated.map(compactResult),
      promoted: promoted ? best.id : champion.id,
      gate: {
        beatsParent,
        protectsWorstCase,
        requiredDelta: minDelta,
        maxWorstDrop: worstDrop
      }
    });

    if (promoted) {
      champion = { id: best.id, label: best.label, config: best.config };
      championEval = best;
      console.error(`[EVOLVE] promoted ${best.id}: overall=${best.overall.toFixed(3)} holdout=${best.holdout.mean.toFixed(3)}`);
    } else {
      console.error(`[EVOLVE] no promotion in generation ${generation}; parent remains champion.`);
    }
  }

  const activePath = join(evolutionHome, 'active.json');
  const reportPath = join(evolutionHome, 'latest-report.json');
  const report = {
    experimentId,
    createdAt: new Date().toISOString(),
    sourceConfig: configPath,
    benchmarkSuite: suitePath,
    policy: { generations, population, minDelta, worstDrop, agents, holdoutWeight: 0.6 },
    champion: compactResult(championEval),
    history
  };
  await writeFile(activePath, JSON.stringify(champion.config, null, 2) + '\n');
  await writeFile(reportPath, JSON.stringify(report, null, 2) + '\n');

  console.log(JSON.stringify({
    experimentId,
    champion: report.champion,
    activeConfig: activePath,
    report: reportPath
  }, null, 2));

  if (flags['keep-work'] !== true && String(flags['keep-work']) !== '1') {
    for (const entry of history.flatMap((h) => h.candidates || [])) {
      const candidateState = join(workRoot, entry.id, 'state');
      await rm(candidateState, { recursive: true, force: true });
    }
  }
}

main().catch((error) => {
  console.error(`FORGE evolution failed: ${error?.stack || error}`);
  process.exitCode = 1;
});
