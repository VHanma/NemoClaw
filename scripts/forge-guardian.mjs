#!/usr/bin/env node
import { access, appendFile, readFile, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

function args(argv) {
  const out = {};
  for (const a of argv) if (a.startsWith('--')) {
    const [k, ...v] = a.slice(2).split('='); out[k] = v.length ? v.join('=') : true;
  }
  return out;
}
async function exists(p){ try { await access(p, constants.R_OK); return true; } catch { return false; } }
async function json(p, fallback=null){ try { return JSON.parse(await readFile(p,'utf8')); } catch { return fallback; } }
async function jsonl(p){ try { return (await readFile(p,'utf8')).split('\n').filter(Boolean).map(x=>{try{return JSON.parse(x)}catch{return null}}).filter(Boolean); } catch { return []; } }
function n(v,d){ const x=Number(v); return Number.isFinite(x)?x:d; }

async function main(){
  const f=args(process.argv.slice(2));
  const root=process.env.FORGE_HOME || join(homedir(),'.forge-hydra');
  const evo=join(root,'evolution');
  const active=join(evo,'active.json');
  const previous=join(evo,'previous.json');
  const history=join(evo,'champion-history.jsonl');
  const feedback=join(root,'feedback.jsonl');
  const minCount=Math.max(2,Math.floor(n(f['min-feedback'],3)));
  const threshold=Math.max(0,Math.min(10,n(f.threshold,4.5)));
  const quiet=Boolean(f.quiet);
  if (!(await exists(active)) || !(await exists(previous))) {
    if(!quiet) console.error('[GUARDIAN] no rollback pair available.');
    return;
  }
  const hist=await jsonl(history);
  const lastPromotion=[...hist].reverse().find(x=>x?.event==='promotion');
  if(!lastPromotion?.at){ if(!quiet) console.error('[GUARDIAN] no promotion timestamp yet.'); return; }
  const feedbackRows=(await jsonl(feedback)).filter(x=>Number.isFinite(Number(x?.score)) && new Date(x.at) >= new Date(lastPromotion.at));
  if(feedbackRows.length < minCount){ if(!quiet) console.error(`[GUARDIAN] ${feedbackRows.length}/${minCount} post-promotion feedback samples.`); return; }
  const recent=feedbackRows.slice(-Math.max(minCount,6));
  const mean=recent.reduce((s,x)=>s+Number(x.score),0)/recent.length;
  if(mean >= threshold){ if(!quiet) console.error(`[GUARDIAN] champion healthy: ${mean.toFixed(2)}/10.`); return; }

  const current=await json(active); const prior=await json(previous);
  if(!prior) throw new Error('previous champion unreadable');
  await writeFile(active,JSON.stringify(prior,null,2)+'\n','utf8');
  await appendFile(history,JSON.stringify({
    event:'rollback', at:new Date().toISOString(), reason:`live feedback mean ${mean.toFixed(2)} below ${threshold}`,
    feedbackCount:recent.length, fromVersion:current?.version ?? null, toVersion:prior?.version ?? null
  })+'\n','utf8');
  console.error(`[GUARDIAN] rollback activated: ${mean.toFixed(2)}/10 < ${threshold}/10.`);
}
main().catch(e=>{ console.error(`GUARDIAN failed: ${e?.stack||e}`); process.exitCode=1; });
