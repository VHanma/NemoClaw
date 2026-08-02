# APEX FORGE / HYDRA

FORGE is an experimental orchestration layer for NemoClaw/OpenClaw. It does **not** rewrite model weights. It improves the surrounding reasoning process by running competing specialist strategies, auditing the judge, red-teaming the winner, regression-testing the refinement, carrying reusable strategy rules into later runs, evolving cognition under benchmark pressure, and routing different reasoning stages through independently configurable model backends.

## Core reasoning pipeline

One task becomes this pipeline:

1. Spawn independent specialist candidates from `config/forge-hydra.json`.
2. Route each specialist through the configured cognitive species layer.
3. Judge every candidate with a weighted rubric.
4. Run **Meta-HYDRA** to audit the judge and override a bad selection.
5. Red-team the selected winner.
6. Synthesize a repaired answer.
7. Run a regression verifier that compares the repaired answer with the original winner and rejects the repair if it became worse.
8. Extract reusable reasoning mutations.
9. Store run history, mutations, species telemetry, calibration, and external feedback under `~/.forge-hydra/`.

## APEX launcher

Use the launcher for normal work:

```bash
node scripts/forge-apex.mjs "Design a better memory architecture for this agent"
```

APEX runs Guardian, refreshes confidence calibration, loads the evolved cognition champion, enables the species router when available, and then starts HYDRA.

## Give outcome feedback

```bash
node scripts/forge-hydra.mjs feedback RUN_ID 9 "Worked, but the setup step was incomplete"
```

Low-performing learned strategies are suppressed from future prompt memory. External outcomes also feed Guardian rollback and confidence calibration.

## Evolutionary cognition

```bash
node scripts/forge-apex.mjs evolve --generations=2 --population=6
```

Darwin wraps `forge-evolve.mjs`, fingerprints the parent and champion, preserves the previous champion for rollback, records ancestry, and automatically merges curated Predator benchmarks into the tournament.

## Predator benchmarks

```bash
node scripts/forge-apex.mjs predator
```

Predator converts real low-rated outcomes into new adversarial holdout tests. A separate curator rejects ambiguous, subjective, private, hidden-knowledge, or malformed proposals before they enter `~/.forge-hydra/predator/active.json`.

## Guardian rollback

```bash
node scripts/forge-apex.mjs health
```

If enough post-promotion feedback falls below the configured threshold, Guardian restores the previous champion and records the rollback in the lineage log.

## Cognitive species

`config/forge-species.json` maps HYDRA stages to model backends. The default configuration uses only the current OpenClaw backend, so existing behavior remains unchanged until another species is enabled.

A species can be:

- `openclaw`: a separate OpenClaw-compatible executable or wrapper;
- `command`: any executable with an explicit argv template using `{prompt}`, `{sessionId}`, `{agent}`, and `{stage}` placeholders.

No shell-string execution is used. Backends run through explicit executable + argument arrays, each with its own timeout and fallback behavior.

Example routing concept:

```json
{
  "routes": {
    "builder": ["primary"],
    "skeptic": ["secondary"],
    "judge": ["secondary"],
    "meta-judge": ["primary"],
    "synth": ["primary"]
  }
}
```

This makes Builder, Skeptic, Judge, and Meta-HYDRA capable of being genuinely different model families instead of merely different personas of the same model.

NemoClaw itself routes sandbox inference through `inference.local` and supports OpenAI, Anthropic, Gemini, NVIDIA, compatible endpoints, and local inference. Because cross-provider switching can require sandbox reconfiguration, FORGE species are intentionally modeled as separate backends/wrappers rather than rapidly racing one shared sandbox route.

### Evolve the species map

Enable at least two species, then run:

```bash
node scripts/forge-apex.mjs species-evolve --population=8
```

`forge-species-evolve.mjs` mutates which species handles which HYDRA stage, benchmarks each routing genome on training and held-out tasks, protects worst-case holdout performance, and promotes a stronger routing map to:

```text
~/.forge-hydra/species/active.json
```

The prior routing genome is preserved at `species/previous.json`.

## Confidence calibration

Run manually:

```bash
node scripts/forge-apex.mjs calibrate
```

APEX also refreshes calibration automatically before ordinary tasks. `forge-calibrate.mjs` compares historical Meta-HYDRA confidence with actual feedback outcomes and writes:

```text
~/.forge-hydra/calibration.json
```

The species runner injects this history into Judge, Meta-HYDRA, and Verifier prompts. If historical confidence has been systematically too high, evaluators are explicitly told to demand stronger evidence before assigning high confidence. If it has been systematically too low, they are told not to understate conclusions that survive verification.

## State files

FORGE creates these outside the repository:

- `runs.jsonl`: task, judge reports, critiques, verifier results, and final answers.
- `strategies.json`: reusable strategy mutations.
- `feedback.jsonl`: external outcome ratings.
- `calibration.json`: confidence-vs-outcome calibration memory.
- `species-runs.jsonl`: backend routing, fallback, latency, and stage telemetry.
- `evolution/active.json`: current evolved cognition champion.
- `evolution/previous.json`: rollback cognition candidate.
- `evolution/champion-history.jsonl`: promotion, survival, and rollback lineage.
- `species/active.json`: current evolved species-routing genome.
- `species/previous.json`: prior species-routing genome.
- `species/latest-report.json`: latest species tournament result.
- `predator/active.json`: curated adversarial holdout tasks.
- `predator/history.jsonl`: Predator benchmark creation history.

## Runtime controls

| Variable | Meaning |
| --- | --- |
| `FORGE_AGENT` | OpenClaw agent name, default `main` |
| `FORGE_OPENCLAW_BIN` | Base OpenClaw executable before species routing |
| `FORGE_LOCAL` | `1` adds `--local`; set `0` to omit it |
| `FORGE_HOME` | Persistent FORGE state directory |
| `FORGE_CONFIG` | Alternate cognition config JSON |
| `FORGE_SPECIES_CONFIG` | Alternate species-routing JSON |
| `FORGE_SPECIES_DISABLE` | `1` bypasses the species router |
| `FORGE_SPECIES_BASE_BIN` | OpenClaw-compatible executable used by the primary species |
| `FORGE_SPECIES_SECONDARY_BIN` | Example secondary backend executable |
| `FORGE_SPECIES_SECONDARY_ARGS_JSON` | JSON argv template for the secondary backend |
| `FORGE_CONCURRENCY` | Max specialist calls running at once |
| `FORGE_TIMEOUT_MS` | Per-agent timeout |
| `FORGE_DARWIN_TIMEOUT_MS` | Maximum Darwin/evolution wrapper runtime |

## Mutable cognition and selection pressure

`config/forge-hydra.json` evolves reasoning roles and rubric weights. `config/forge-species.json` evolves which model family handles each role.

The architecture therefore has four distinct pressures:

- **internal adversarial pressure** from HYDRA, Meta-HYDRA, red-team, and regression verification;
- **cognition selection pressure** from train/holdout evolution of role directives and rubric weights;
- **species selection pressure** from train/holdout evolution of model routing;
- **live outcome pressure** from external feedback, calibration, strategy suppression, and Guardian rollback.

## Important limitations

This still does not alter protected model weights. The improvement target is the external cognition system: prompts, roles, evaluators, memory, benchmark curriculum, routing, and backend selection.

True multi-model diversity exists only after multiple species are actually configured. With the default config, every stage still uses the primary OpenClaw backend.

Provider credentials and backend installation remain external operational requirements. FORGE deliberately does not copy secrets into its routing config or logs.

The remaining frontier is increasingly expensive rather than conceptually mysterious: larger benchmark ecosystems, more independent model families, domain-specific champions, executable coding/retrieval tests, delayed forecasting resolution, and long-running empirical comparison of species lineages.
