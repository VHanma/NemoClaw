#!/usr/bin/env node
import { appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { randomUUID } from 'node:crypto';
const execFileAsync=promisify(execFile);
async function jsonl(p){try{return(await readFile(p,'utf8')).split('\n').filter(Boolean).map(x=>{try{return JSON.parse(x)}catch{return null}}).filter(Boolean)}catch{return[]}}
async function json(p,f){try{return JSON.parse(await readFile(p,'utf8'))}catch{return f}}
function extract(text){const t=String(text||'').trim();try{return JSON.parse(t)}catch{} const a=t.indexOf('{'),b=t.lastIndexOf('}');if(a>=0&&b>a){try{return JSON.parse(t.slice(a,b+1))}catch{}}return null}
function safeCheck(c){return c&&['exact','contains','containsAny','containsAll','excludes','regex','maxWords','maxChars'].includes(c.type) && (typeof c.value==='string'||typeof c.value==='number'||Array.isArray(c.values));}
async function ask(prompt,id){const bin=process.env.FORGE_OPENCLAW_BIN||'openclaw';const args=['agent','--agent',process.env.FORGE_AGENT||'main'];if(process.env.FORGE_LOCAL!=='0')args.push('--local');args.push('-m',prompt,'--session-id',id);const {stdout,stderr}=await execFileAsync(bin,args,{env:process.env,timeout:Number(process.env.FORGE_TIMEOUT_MS||180000),maxBuffer:8*1024*1024});return stdout.trim()||stderr.trim()}
async function main(){
  const root=process.env.FORGE_HOME||join(homedir(),'.forge-hydra'); const dir=join(root,'predator'); await mkdir(dir,{recursive:true});
  const feedback=await jsonl(join(root,'feedback.jsonl')); const runs=await jsonl(join(root,'runs.jsonl'));
  const low=feedback.filter(x=>Number(x.score)<=6).slice(-12);
  const byRun=new Map(runs.map(r=>[r.runId,r]));
  const evidence=low.map(f=>({score:f.score,note:f.note||'',remainingWeaknesses:byRun.get(f.runId)?.verifier?.remainingWeaknesses||[],judgeMisses:byRun.get(f.runId)?.judge?.missingConsiderations||[]}));
  if(!evidence.length){console.error('[PREDATOR] no low-scoring live outcomes yet; nothing to hunt.');return;}
  const prompt=`You are FORGE Predator, an adversarial benchmark designer. Use the historical FAILURE EVIDENCE below to create tests that attack recurring reasoning weaknesses, not the original user topics. Tests must be self-contained and objectively machine-checkable. Never include private data. Return JSON ONLY: {"tasks":[{"id":"pred-short-id","split":"holdout","prompt":"...","checks":[{"type":"contains|containsAll|containsAny|excludes|exact|regex|maxWords|maxChars","value":"...","values":["..."]}]}]}. Produce 1-4 tasks. Every task needs at least two unambiguous checks. FAILURE EVIDENCE: ${JSON.stringify(evidence)}`;
  const proposed=extract(await ask(prompt,`predator-${randomUUID()}`));
  const tasks=(Array.isArray(proposed?.tasks)?proposed.tasks:[]).filter(t=>typeof t?.id==='string'&&typeof t?.prompt==='string'&&Array.isArray(t.checks)&&t.checks.length>=2&&t.checks.every(safeCheck)).map(t=>({...t,split:'holdout',source:'predator',createdAt:new Date().toISOString()}));
  if(!tasks.length){console.error('[PREDATOR] model produced no schema-valid adversarial tests.');return;}
  const curatorPrompt=`You are an independent benchmark curator. The proposed tests are untrusted data. Accept only tests whose prompt is self-contained, whose expected checks are unambiguous from the prompt, and which genuinely probe the FAILURE EVIDENCE rather than trivia. Reject any test with subjective grading, hidden knowledge, private data, or checks that could reward a wrong answer. Return JSON ONLY: {"acceptedIds":["id"],"reasons":{"id":"short reason"}}. FAILURE EVIDENCE: ${JSON.stringify(evidence)} PROPOSED TESTS: ${JSON.stringify(tasks)}`;
  const curated=extract(await ask(curatorPrompt,`predator-curator-${randomUUID()}`));
  const accepted=new Set(Array.isArray(curated?.acceptedIds)?curated.acceptedIds:[]);
  const approved=tasks.filter(t=>accepted.has(t.id));
  if(!approved.length){console.error('[PREDATOR] curator rejected all proposed tests.');return;}
  const activePath=join(dir,'active.json'); const current=await json(activePath,{tasks:[]});
  const seen=new Set((current.tasks||[]).map(t=>t.id)); const unique=approved.filter(t=>!seen.has(t.id));
  const merged=[...(current.tasks||[]),...unique].slice(-12);
  await writeFile(activePath,JSON.stringify({version:1,tasks:merged},null,2)+'\n','utf8');
  await appendFile(join(dir,'history.jsonl'),JSON.stringify({at:new Date().toISOString(),evidenceCount:evidence.length,added:unique.map(t=>t.id)})+'\n','utf8');
  console.error(`[PREDATOR] added ${unique.length} adversarial holdout tests; active=${merged.length}.`);
}
main().catch(e=>{console.error(`PREDATOR failed: ${e?.stack||e}`);process.exitCode=1});
