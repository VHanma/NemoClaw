#!/usr/bin/env node
import { readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { join } from 'node:path';

async function readJsonl(path){try{return (await readFile(path,'utf8')).split('\n').filter(Boolean).map(x=>{try{return JSON.parse(x)}catch{return null}}).filter(Boolean)}catch{return[]}}
function mean(xs){return xs.length?xs.reduce((a,b)=>a+b,0)/xs.length:0}
async function main(){
  const root=process.env.FORGE_HOME||join(homedir(),'.forge-hydra');
  const runs=await readJsonl(join(root,'runs.jsonl'));
  const feedback=await readJsonl(join(root,'feedback.jsonl'));
  const byRun=new Map(); for(const f of feedback){const n=Number(f.score);if(f.runId&&Number.isFinite(n)){const a=byRun.get(f.runId)||[];a.push(n);byRun.set(f.runId,a)}}
  const samples=[];
  for(const r of runs){const ys=byRun.get(r.runId);const c=Number(r?.metaJudge?.confidence);if(!ys?.length||!Number.isFinite(c))continue;samples.push({confidence:Math.max(0,Math.min(1,c)),outcome:mean(ys)})}
  const errors=samples.map(s=>s.confidence*10-s.outcome);
  const bins=[]; for(let i=0;i<5;i++){const lo=i*.2,hi=(i+1)*.2;const xs=samples.filter(s=>s.confidence>=lo&&(i===4?s.confidence<=hi:s.confidence<hi));bins.push({lo,hi,count:xs.length,meanConfidence:mean(xs.map(x=>x.confidence)),meanOutcome:mean(xs.map(x=>x.outcome))})}
  const out={at:new Date().toISOString(),sampleCount:samples.length,mae:mean(errors.map(Math.abs)),bias:mean(errors),bins};
  await writeFile(join(root,'calibration.json'),JSON.stringify(out,null,2)+'\n','utf8');
  console.log(JSON.stringify(out,null,2));
}
main().catch(e=>{console.error(`CALIBRATE failed: ${e?.stack||e}`);process.exitCode=1});
