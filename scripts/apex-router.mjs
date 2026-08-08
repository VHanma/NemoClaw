#!/usr/bin/env node
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));
const CORE = join(HERE, 'apex-lite.mjs');
const args = process.argv.slice(2);
const commands = new Set(['new','use','current','list','status','providers','free','sync']);

function routeFor(task) {
  const t = String(task || '').toLowerCase();

  // Directly addressed figures outrank automatic routing.
  const direct = [
    'yujiro','baki','tesla','gariaev','bearden','levin','batman','kozyrev','athena','musashi','sun tzu',
    'hermes','jung','morgan le fay','deadpool','sheogorath','shikamaru','lelouch','doctor manhattan',
    'doctor strange','cicero','light yagami','near','johnny silverhand','bulma','doc brown','farnsworth',
    'whis','laozi','goku','mckenna','aizen','bruce lee','doctor doom','l '
  ];
  if (direct.some(name => t.includes(name))) {
    return 'The named character or characters directly addressed by the user take priority. Other Court members speak only if their own expertise adds materially distinct value. Rank alone never grants a speaking turn.';
  }

  if (/physics|science|gravity|spacetime|electromagnet|\bemf\b|frequency|resonance|light|field|time dilation|relativity|quantum|biology|dna|bioelectric|experiment/.test(t)) {
    return 'Science Frontier — Tesla + Gariaev + Bearden + Michael Levin + Batman + Kozyrev is the lead seat for this task. Let only the members of that seat whose expertise truly applies speak, each fully in character. Yujiro and Baki remain silent unless a genuinely relevant Hanma-domain contribution materially helps the task. Do not use Yujiro as narrator or final voice merely because he is King.';
  }
  if (/code|app|apk|android|termux|github|software|network|computer|cyber|program|script|api/.test(t)) {
    return 'Computational Mind — Johnny Silverhand + Master Netrunner is the lead seat for this task, with Systems Thinker or Future Architect entering only for distinct useful contributions. Yujiro and Baki remain silent unless directly relevant.';
  }
  if (/dream|symbol|jung|occult|hermetic|alchemy|archetype|ritual|myth/.test(t)) {
    return 'Magician — Carl Jung + Hermes Trismegistus is the lead seat for this task. Other symbolic or skeptical specialists may enter only when they add a distinct perspective. Yujiro and Baki remain silent unless directly relevant.';
  }
  if (/negotiate|mediat|agreement|compromise|settlement/.test(t)) {
    return 'Mediator — Doctor Strange + Doctor Doom is the lead seat for this task. Other Court members contribute only where their own domain adds new leverage or constraints.';
  }
  if (/speech|rhetoric|persuad|explain|presentation|narrat/.test(t)) {
    return 'Orator — Cicero + Baki Narrator is the lead seat for this task. Other Court members contribute only if they add distinct subject-matter expertise.';
  }
  if (/future|invent|prototype|time machine|forecast|scenario/.test(t)) {
    return 'Future Architect — Bulma + Doc Brown + Professor Farnsworth is the lead seat for this task, with Science Frontier or Speculator entering only for distinct useful additions.';
  }
  if (/fight|combat|boxing|mma|wrestling|jiu-jitsu|muay thai|punch|kick|strike|grappl|sparring/.test(t)) {
    return 'This is inside the Hanma combat domain. Yujiro, Baki, Warrior, and Martial Philosopher may speak when their different expertise adds value, but even here do not force every seat to talk or repeat another point.';
  }
  if (/strategy|war|tactic|opponent|deception|battle/.test(t)) {
    return 'Warrior — Athena + Miyamoto Musashi + Sun Tzu is the lead seat for this task. Yujiro may enter only when his pressure-testing or combat-dominance lens adds something materially distinct.';
  }

  return 'Choose the most relevant domain expert among the selected Court seats as lead. Court rank never determines the final speaker. Yujiro and Baki may remain present as persistent seats but stay silent when the topic is outside their expertise. A character speaks only for a distinct useful contribution.';
}

let forwarded = args;
if (args.length && !commands.has(args[0])) {
  const task = args.join(' ');
  const routing = routeFor(task);
  forwarded = [`APEX DOMAIN-RELEVANCE ROUTING:\n${routing}\n\nORIGINAL USER REQUEST:\n${task}`];
}

const result = spawnSync(process.execPath, [CORE, ...forwarded], { stdio: 'inherit' });
if (result.error) {
  console.error(`[APEX ROUTER] ${result.error.message}`);
  process.exit(1);
}
process.exit(result.status ?? 1);
