#!/usr/bin/env node
import { access } from 'node:fs/promises';
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
async function preflightGuardian(script,env){
  try{const {stderr}=await execFileAsync(process.execPath,[script,'--quiet'],{env,timeout:30000,maxBuffer:1024*1024});if(stderr?.trim())console.error(stderr.trim());}
  catch(e){console.error(`[APEX] guardian preflight skipped: ${e.message}`)}
}
async function main(){
  const argv=process.argv.slice(2);
  const known=new Set(['evolve','predator','health']);
  const command=known.has(argv[0])?argv.shift():'ask';
  const root=process.env.FORGE_HOME||join(homedir(),'.forge-hydra');
  const active=join(root,'evolution','active.json');
  const base=resolve(process.cwd(),'config','forge-hydra.json');
  const hydra=process.env.FORGE_APEX_HYDRA_SCRIPT||resolve(process.cwd(),'scripts','forge-hydra.mjs');
  const darwin=process.env.FORGE_APEX_DARWIN_SCRIPT||resolve(process.cwd(),'scripts','forge-darwin.mjs');
  const predator=process.env.FORGE_APEX_PREDATOR_SCRIPT||resolve(process.cwd(),'scripts','forge-predator.mjs');
  const guardian=process.env.FORGE_APEX_GUARDIAN_SCRIPT||resolve(process.cwd(),'scripts','forge-guardian.mjs');
  if(command==='evolve'){run(darwin,argv,process.env);return}
  if(command==='predator'){run(predator,argv,process.env);return}
  if(command==='health'){run(guardian,argv,process.env);return}
  await preflightGuardian(guardian,process.env);
  const env={...process.env};
  if(!env.FORGE_CONFIG){env.FORGE_CONFIG=await exists(active)?active:base;console.error(`[APEX] cognition=${env.FORGE_CONFIG===active?'evolved champion':'base config'}`)}
  run(hydra,argv,env);
}
main().catch(error=>{console.error(`APEX failed: ${error?.stack||error}`);process.exitCode=1});
