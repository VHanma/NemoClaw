#!/usr/bin/env node
import { access, chmod } from 'node:fs/promises';
import { constants } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawn, execFile } from 'node:child_process';
import { promisify } from 'node:util';
const execFileAsync=promisify(execFile);

async function exists(path){try{await access(path,constants.R_OK);return true}catch{return false}}
function run(script,args,env){
  const child=spawn(process.execPath,[script,...args],{stdio:'inherit',env});
  child.on('exit',(code,signal)=>{if(signal)process.kill(process.pid,signal);else process.exitCode=code??1});
  child.on('error',(error)=>{console.error(`APEX launcher failed: ${error.message}`);process.exitCode=1});
}
async function quiet(script,args,env,timeout=30000){try{await execFileAsync(process.execPath,[script,...args],{env,timeout,maxBuffer:1024*1024})}catch(e){console.error(`[APEX] preflight skipped: ${e.message}`)}}
async function speciesEnv(root,baseEnv){
  const env={...baseEnv};
  if(env.FORGE_SPECIES_DISABLE==='1') return env;
  const runner=process.env.FORGE_APEX_SPECIES_RUNNER||resolve(process.cwd(),'scripts','forge-species-runner.mjs');
  const baseSpecies=resolve(process.cwd(),'config','forge-species.json');
  const activeSpecies=join(root,'species','active.json');
  if(!(await exists(baseSpecies)) && !(await exists(activeSpecies))) return env;
  try{await chmod(runner,0o755)}catch{}
  env.FORGE_SPECIES_BASE_BIN=env.FORGE_SPECIES_BASE_BIN||env.FORGE_OPENCLAW_BIN||'openclaw';
  env.FORGE_OPENCLAW_BIN=runner;
  return env;
}
async function main(){
  const argv=process.argv.slice(2);
  const known=new Set(['evolve','predator','health','species-evolve','calibrate','hydra','pantheon']);
  const command=known.has(argv[0])?argv.shift():'ask';
  const root=process.env.FORGE_HOME||join(homedir(),'.forge-hydra');
  const active=join(root,'evolution','active.json');
  const base=resolve(process.cwd(),'config','forge-hydra.json');
  const hydra=process.env.FORGE_APEX_HYDRA_SCRIPT||resolve(process.cwd(),'scripts','forge-hydra.mjs');
  const pantheon=process.env.FORGE_APEX_PANTHEON_SCRIPT||resolve(process.cwd(),'scripts','forge-pantheon.mjs');
  const darwin=process.env.FORGE_APEX_DARWIN_SCRIPT||resolve(process.cwd(),'scripts','forge-darwin.mjs');
  const predator=process.env.FORGE_APEX_PREDATOR_SCRIPT||resolve(process.cwd(),'scripts','forge-predator.mjs');
  const guardian=process.env.FORGE_APEX_GUARDIAN_SCRIPT||resolve(process.cwd(),'scripts','forge-guardian.mjs');
  const speciesEvolve=process.env.FORGE_APEX_SPECIES_EVOLVE_SCRIPT||resolve(process.cwd(),'scripts','forge-species-evolve.mjs');
  const calibrate=process.env.FORGE_APEX_CALIBRATE_SCRIPT||resolve(process.cwd(),'scripts','forge-calibrate.mjs');
  if(command==='health'){run(guardian,argv,process.env);return}
  if(command==='calibrate'){run(calibrate,argv,process.env);return}
  const routedEnv=await speciesEnv(root,process.env);
  if(command==='evolve'){run(darwin,argv,routedEnv);return}
  if(command==='predator'){run(predator,argv,routedEnv);return}
  if(command==='species-evolve'){run(speciesEvolve,argv,routedEnv);return}
  await quiet(guardian,['--quiet'],process.env,30000);
  await quiet(calibrate,[],process.env,15000);
  const env=await speciesEnv(root,process.env);
  if(!env.FORGE_CONFIG){env.FORGE_CONFIG=await exists(active)?active:base;console.error(`[APEX] cognition=${env.FORGE_CONFIG===active?'evolved champion':'base config'}`)}
  if(env.FORGE_OPENCLAW_BIN?.endsWith('forge-species-runner.mjs')) console.error('[APEX] cognitive-species router=active');
  const usePantheon=command==='pantheon'||(command==='ask'&&env.FORGE_PANTHEON!=='0');
  if(usePantheon){console.error('[APEX] archetype-pantheon=active');run(pantheon,argv,env);return}
  run(hydra,argv,env);
}
main().catch(error=>{console.error(`APEX failed: ${error?.stack||error}`);process.exitCode=1});
