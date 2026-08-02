#!/usr/bin/env node
import { access } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';

async function exists(path) {
  try {
    await access(path, constants.R_OK);
    return true;
  } catch {
    return false;
  }
}

function run(script, args, env) {
  const child = spawn(process.execPath, [script, ...args], {
    stdio: 'inherit',
    env
  });
  child.on('exit', (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exitCode = code ?? 1;
  });
  child.on('error', (error) => {
    console.error(`APEX launcher failed: ${error.message}`);
    process.exitCode = 1;
  });
}

async function main() {
  const argv = process.argv.slice(2);
  const command = argv[0] === 'evolve' ? argv.shift() : 'ask';
  const root = process.env.FORGE_HOME || join(homedir(), '.forge-hydra');
  const active = join(root, 'evolution', 'active.json');
  const base = resolve(process.cwd(), 'config', 'forge-hydra.json');
  const hydra = process.env.FORGE_APEX_HYDRA_SCRIPT || resolve(process.cwd(), 'scripts', 'forge-hydra.mjs');
  const evolve = process.env.FORGE_APEX_EVOLVE_SCRIPT || resolve(process.cwd(), 'scripts', 'forge-evolve.mjs');

  if (command === 'evolve') {
    run(evolve, argv, process.env);
    return;
  }

  const env = { ...process.env };
  if (!env.FORGE_CONFIG) {
    env.FORGE_CONFIG = await exists(active) ? active : base;
    console.error(`[APEX] cognition=${env.FORGE_CONFIG === active ? 'evolved champion' : 'base config'}`);
  }
  run(hydra, argv, env);
}

main().catch((error) => {
  console.error(`APEX failed: ${error?.stack || error}`);
  process.exitCode = 1;
});
