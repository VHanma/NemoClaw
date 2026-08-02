# APEX FORGE / HYDRA

FORGE is an experimental cognition layer for NemoClaw/OpenClaw. It does **not** rewrite protected model weights. It improves the surrounding reasoning system through competing agents, model routing, archetype selection, evaluation, memory, adversarial testing, evolution, calibration, and live rollback.

## APEX stack

A normal APEX task can now pass through:

1. **Guardian** checks whether the current cognition champion should be rolled back from live outcome data.
2. **Calibration** updates evaluator guidance from historical confidence versus actual feedback.
3. **Cognitive Species** chooses the best available model/backend for each reasoning stage.
4. **Pantheon Conductor** selects a small task-specific set of archetypal lenses and collective councils.
5. **HYDRA** produces competing specialist answers.
6. **Judge + Meta-HYDRA** score the candidates and audit the scoring itself.
7. **Red Team** attacks the current winner.
8. **Synthesizer** repairs the answer.
9. **Regression Verifier** can reject the repair if recursion made the answer worse.
10. **Memory** stores reusable strategy mutations and external outcome feedback.
11. **Predator + Darwin** turn real failures into curated adversarial tests and evolve the cognition configuration under train/holdout pressure.

## Run APEX

```bash
node scripts/forge-apex.mjs "Design a better memory architecture for this agent"
```

The Pantheon is enabled by default. Run raw HYDRA without the archetype layer with either:

```bash
node scripts/forge-apex.mjs hydra "your task"
```

or:

```bash
FORGE_PANTHEON=0 node scripts/forge-apex.mjs "your task"
```

## APEX Pantheon

`config/forge-archetypes.json` contains **78 individual cognitive archetypes** and **18 collective councils**. The registry is deliberately broad, not metaphysically exhaustive. The Conductor may create one temporary emergent archetype when a task genuinely needs a missing lens.

Three archetypes are always present:

- **Creator** for originality, invention, aesthetics, and new combinations.
- **Skeptic** for assumption hunting and evidential resistance.
- **Integrator** for resolving the full debate without flattening meaningful disagreement.

The wider registry includes Jungian-core, epistemic, engineering, strategic, adversarial, creative, transformative, temporal, human, philosophical, shadow/lateral, and meta-cognitive families. Examples include Scientist, Mathematician, Engineer, Architect, Strategist, Black Swan, Artist, Poet, Inventor, Alchemist, Futurist, Forecaster, Empath, Psychologist, Philosopher, Symbolist, Shadow, Trickster, Contrarian, Conductor, and Meta-Critic.

Collective councils include **Whole Mind, Collective Unconscious, Creative Studio, Scientific Academy, Engineering Forge, War Room, Truth Tribunal, Shadow Cabinet, Future Observatory, Human Council, Ethics Council, Executive Board, Research Guild, Innovation Lab, Dialectic Engine, Crisis Cell, Story Room, and Meta Council**.

Archetypes are reasoning lenses, not separate consciousnesses or supernatural authorities. Symbolic lenses are explicitly instructed not to turn metaphor, intuition, dreams, myths, or archetypal resemblance into factual evidence.

### Dynamic selection

The Conductor normally selects only 4–10 archetypes and up to two councils. Running the entire Pantheon on every prompt would increase cost, latency, and correlated noise.

Selection uses:

- the task itself;
- required diversity, with Creator + Skeptic + Integrator always included;
- historical outcome scores when enough samples exist;
- one optional emergent lens;
- a deterministic keyword fallback if the Conductor backend is unavailable.

`pantheon-runs.jsonl` records which archetypes and councils were used. The final HYDRA run ID is linked to external feedback, allowing future selection to learn which lenses have historically correlated with better or worse outcomes. This is treated as a useful signal, not proof of causality.

## Elite Cognitive Species

`config/forge-species.json` assigns reasoning stages and archetypes to independently configurable model backends. Species are enabled only when their required credentials or wrappers exist; otherwise routing falls through to the next available backend and ultimately to the primary OpenClaw environment.

The default frontier-oriented roster includes:

| Species | Intended strengths |
| --- | --- |
| GPT-5.6 Sol | frontier synthesis, research, coding, systems reasoning, meta-cognition |
| GPT-5.6 Terra | balanced frontier reasoning and subagent work |
| GPT-5.6 Luna | fast/high-volume support agents |
| Claude Fable 5 | long-horizon coding, judgment, critique, hard knowledge work |
| Claude Sonnet 5 | efficient agentic execution, coding, and tool use |
| Gemini 3.1 Deep Think wrapper | mathematics, science, engineering, formal reasoning |
| Gemini 3.1 Pro | multimodal reasoning, creative concepts, design ideation |
| Gemini 3.6 Flash | fast multimodal/spatial scouting and high-throughput loops |
| Grok 4.5 | independent engineering/coding line, lateral and contrarian exploration |
| Kimi K2.7 Code compatible route | open-weight independent coding/reasoning lineage |
| GLM-5.2 compatible route | open-weight long-context and long-horizon lineage |
| Primary OpenClaw | reliable fallback and connected local environment |

This is a starting genome, not an eternal leaderboard. Model quality changes. `forge-species-evolve.mjs` exists so routing assignments can be benchmarked and evolved rather than frozen to vendor claims.

### Credentials / optional routes

The standard direct API species use environment variables rather than storing credentials in repository files or telemetry:

```text
OPENAI_API_KEY
ANTHROPIC_API_KEY
GEMINI_API_KEY
XAI_API_KEY
```

Optional specialist routes:

```text
FORGE_GEMINI_DEEP_BIN
FORGE_GEMINI_DEEP_ARGS_JSON
FORGE_KIMI_BASE_URL
FORGE_KIMI_API_KEY
FORGE_GLM_BASE_URL
FORGE_GLM_API_KEY
```

`forge-api-backend.mjs` provides API adapters. `forge-species-runner.mjs` uses explicit executable + argv arrays rather than shell-string execution and logs only routing telemetry, not credentials.

## Feedback and confidence calibration

After testing an answer in the real world:

```bash
node scripts/forge-hydra.mjs feedback RUN_ID 9 "Worked, but the setup step was incomplete"
```

External feedback can:

- suppress strategy mutations originating from poorly rated runs;
- inform Pantheon archetype/council selection;
- calibrate Meta-HYDRA confidence;
- trigger Guardian rollback when a newly promoted champion underperforms after deployment.

## Evolution

Run cognition evolution:

```bash
node scripts/forge-apex.mjs evolve --generations=2 --population=6
```

Run model-routing evolution:

```bash
node scripts/forge-apex.mjs species-evolve
```

Generate new adversarial tests from recurring real failures:

```bash
node scripts/forge-apex.mjs predator
```

Check champion health:

```bash
node scripts/forge-apex.mjs health
```

Evolution uses train/holdout scoring, improvement thresholds, and worst-case regression limits. Darwin records parent/child fingerprints. Guardian preserves the previous champion and can restore it if live performance deteriorates.

## Persistent state

FORGE stores runtime learning under `~/.forge-hydra/`, including:

- `runs.jsonl` — HYDRA runs and final answers
- `strategies.json` — reusable reasoning mutations
- `feedback.jsonl` — external outcome scores
- `calibration.json` — confidence calibration summary
- `pantheon-runs.jsonl` — archetype and council selections linked to child HYDRA runs
- `species-runs.jsonl` — model/backend routing telemetry
- `evolution/active.json` — current cognition champion
- `evolution/previous.json` — rollback candidate
- `evolution/champion-history.jsonl` — promotion/survival/rollback lineage
- `species/active.json` — current routing genome
- `predator/active.json` — curated adversarial holdout tasks

## Practical limit

The architecture can evolve prompts, roles, archetype mixtures, model routing, evaluation criteria, memories, benchmark curriculum, and rollback policy. It still cannot secretly rewrite protected provider model weights or manufacture provider access.

Beyond this point, the biggest gains are empirical: connect genuinely different top-tier backends, expand high-quality executable benchmarks, collect real outcome data, specialize champions by domain, and run enough controlled evolutionary trials to distinguish genuine improvement from benchmark luck.
