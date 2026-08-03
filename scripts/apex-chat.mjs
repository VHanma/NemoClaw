#!/usr/bin/env node
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';

function safeId(value) {
  const s = String(value || '').trim().replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80);
  return s || `chat-${randomUUID().slice(0, 8)}`;
}

async function readText(path, fallback = '') {
  try { return (await readFile(path, 'utf8')).trim(); } catch { return fallback; }
}

function extractChatFlag(args) {
  for (const arg of args) if (arg.startsWith('--chat=')) return safeId(arg.slice(7));
  return '';
}

function withoutChatFlag(args) {
  return args.filter(arg => !arg.startsWith('--chat='));
}

async function main() {
  const root = process.env.FORGE_HOME || join(homedir(), '.forge-hydra');
  const continuityRoot = join(root, 'continuity');
  const activeFile = join(continuityRoot, '.active-chat');
  await mkdir(continuityRoot, { recursive: true });

  const args = process.argv.slice(2);
  const command = args[0];

  if (command === 'new') {
    const id = safeId(args.slice(1).join('-') || `chat-${new Date().toISOString().slice(0, 10)}-${randomUUID().slice(0, 6)}`);
    await writeFile(activeFile, id + '\n', 'utf8');
    await mkdir(join(continuityRoot, id), { recursive: true });
    console.log(id);
    return;
  }

  if (command === 'use') {
    const id = safeId(args.slice(1).join('-'));
    await writeFile(activeFile, id + '\n', 'utf8');
    await mkdir(join(continuityRoot, id), { recursive: true });
    console.log(id);
    return;
  }

  if (command === 'current') {
    console.log(await readText(activeFile, 'main'));
    return;
  }

  if (command === 'list') {
    const entries = await readdir(continuityRoot, { withFileTypes: true }).catch(() => []);
    const active = await readText(activeFile, 'main');
    for (const entry of entries.filter(e => e.isDirectory()).sort((a, b) => a.name.localeCompare(b.name))) {
      console.log(`${entry.name === active ? '* ' : '  '}${entry.name}`);
    }
    return;
  }

  if (!args.length) {
    console.error('Usage: apex-chat "message" | apex-chat new [name] | apex-chat use <name> | apex-chat list | apex-chat current');
    process.exitCode = 2;
    return;
  }

  const explicit = extractChatFlag(args);
  const inherited = process.env.FORGE_CHAT_ID || process.env.APEX_CHAT_ID || process.env.OPENCLAW_SESSION_ID || process.env.CHAT_ID || process.env.SESSION_ID;
  const active = await readText(activeFile, 'main');
  const chatId = safeId(explicit || inherited || active || 'main');
  await writeFile(activeFile, chatId + '\n', 'utf8');

  const apex = process.env.APEX_FORGE_LAUNCHER || resolve(process.cwd(), 'scripts', 'forge-apex.mjs');
  const forwarded = withoutChatFlag(args);
  forwarded.push(`--chat=${chatId}`);

  const env = {
    ...process.env,
    FORGE_CHAT_ID: chatId,
    FORGE_PANTHEON: '1',
    FORGE_SPECIES_DISABLE: '0',
    FORGE_ALL_UPGRADES: '1'
  };

  console.error(`[APEX CHAT] chat=${chatId} full-upgrade-stack=on`);
  const child = spawn(process.execPath, [apex, ...forwarded], { stdio: 'inherit', env });
  child.on('exit', (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exitCode = code ?? 1;
  });
  child.on('error', error => {
    console.error(`APEX CHAT failed: ${error.message}`);
    process.exitCode = 1;
  });
}

main().catch(error => {
  console.error(`APEX CHAT failed: ${error?.stack || error}`);
  process.exitCode = 1;
});
