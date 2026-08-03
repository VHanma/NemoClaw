#!/usr/bin/env node
import { access, appendFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { createHash } from 'node:crypto';
const execFileAsync=promisify(execFile);
async function exists(p){try{await access(p,constants.R_OK);return true}catch{return false}}
async function readJson(p,f=null){try{return JSON.parse(await readFile(p,'utf8'))}catch{return f}}
function fp(v){return createHash('sha256').update(JSON.stringify(v)).digest('hex').slice(0,16)}
async function main(){
  const argv=process.argv.slice(2);
  const root=process.env.FORGE_HOME || join(homedir(),'.forge-hydra');
  const evo=join(root,'evolution'); await mkdir(evo,{recursive:true});
  const active=join(evo,'active.json'); const previous=join(evo,'previous.json'); const history=join(evo,'champion-history.jsonl');
  const base=resolve(process.cwd(),'config','forge-hydra.json');
  const evolve=process.env.FORGE_DARWIN_EVOLVE_SCRIPT || resolve(process.cwd(),'scripts','forge-evolve.mjs');
  const baseSuite=resolve(process.cwd(),'config','forge-benchmarks.json');
  const predatorSuite=join(root,'predator','active.json');
  const before=await readJson((await exists(active))?active:base);
  const beforeFp=fp(before);
  let evolveArgs=[...argv];
  if(!argv.some(a=>a.startsWith('--suite=')) && await exists(predatorSuite)) {
    const baseData=await readJson(baseSuite,{tasks:[]}); const pred=await readJson(predatorSuite,{tasks:[]});
    const merged={...baseData,tasks:[...(baseData.tasks||[]),...(pred.tasks||[])]};
    const mergedPath=join(evo,'merged-benchmarks.json'); await writeFile(mergedPath,JSON.stringify(merged,null,2)+'\n','utf8');
    evolveArgs.push(`--suite=${mergedPath}`);
    console.error(`[DARWIN] merged ${(pred.tasks||[]).length} curated predator tests into evolution.`);
  }
  const {stdout,stderr}=await execFileAsync(process.execPath,[evolve,...evolveArgs],{env:process.env,maxBuffer:32*1024*1024,timeout:Number(process.env.FORGE_DARWIN_TIMEOUT_MS||1800000)});
  if(stdout) process.stdout.write(stdout); if(stderr) process.stderr.write(stderr);
  const after=await readJson((await exists(active))?active:base);
  const afterFp=fp(after);
  if(afterFp!==beforeFp){
    await writeFile(previous,JSON.stringify(before,null,2)+'\n','utf8');
    await appendFile(history,JSON.stringify({event:'promotion',at:new Date().toISOString(),parentFingerprint:beforeFp,childFingerprint:afterFp,parentVersion:before?.version??null,childVersion:after?.version??null,mutation:after?.evolution?.mutation??null})+'\n','utf8');
    console.error(`[DARWIN] lineage recorded ${beforeFp} -> ${afterFp}`);
  } else {
    await appendFile(history,JSON.stringify({event:'survival',at:new Date().toISOString(),fingerprint:afterFp,version:after?.version??null})+'\n','utf8');
    console.error(`[DARWIN] champion survived unchanged ${afterFp}`);
  }
}
main().catch(e=>{console.error(`DARWIN failed: ${e?.stack||e}`);process.exitCode=1});
