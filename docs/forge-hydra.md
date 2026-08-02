# APEX FORGE / HYDRA

FORGE is an experimental orchestration layer for NemoClaw/OpenClaw. It does **not** rewrite model weights. It improves the surrounding reasoning process by running competing specialist strategies, auditing the judge, red-teaming the winner, regression-testing the refinement, carrying reusable strategy rules into later runs, and evolving the external cognition configuration under benchmark pressure.

## Core reasoning pipeline

One task becomes this pipeline:

1. Spawn independent specialist candidates from `config/forge-hydra.json`.
2. Judge every candidate with a weighted rubric.
3. Run **Meta-HYDRA** to audit the judge and override a bad selection.
4. Red-team the selected winner.
5. Synthesize a repaired answer.
6. Run a regression verifier that compares the repaired answer with the original winner and rejects the repair if it became worse.
7. Extract up to three reusable reasoning mutations.
8. Store run history, mutations, and optional human feedback under `~/.forge-hydra/`.
9. Feed trusted strategy rules and recent external feedback into later runs.

This gives the system a mutable cognitive layer even when the underlying model itself is fixed.

## APEX launcher

Use the launcher for normal work so it automatically loads the current evolved champion and performs a Guardian health check before each task:

```bash
node scripts/forge-apex.mjs "Design a better memory architecture for this agent"
```

Direct HYDRA execution is still available:

```bash
node scripts/forge-hydra.mjs "your task" --agents=4
```

## Give outcome feedback

After testing an answer in the real world, feed the result back into FORGE:

```bash
node scripts/forge-hydra.mjs feedback RUN_ID 9 "Worked, but the setup step was incomplete"
```

Recent feedback is included in later runs. Self-evaluation alone can reinforce its own mistakes, so external outcomes provide an independent signal.

FORGE links each learned mutation to the run that created it. If that originating run later receives an average external rating below **5/10**, its mutations are suppressed from future prompt memory. Unrated mutations remain provisional and are ranked by the mutator's confidence until real outcome data arrives.

## Evolutionary cognition

Run an evolutionary tournament through Darwin:

```bash
node scripts/forge-apex.mjs evolve --generations=2 --population=6
```

Darwin wraps `forge-evolve.mjs`, fingerprints the parent and resulting champion, saves the previous champion for rollback, and records ancestry in:

```text
~/.forge-hydra/evolution/champion-history.jsonl
```

If curated Predator benchmarks exist, Darwin automatically merges them with the static benchmark suite before evolution. A variant is still subject to the evolution controller's held-out scoring, improvement threshold, and worst-case regression protection.

## Predator benchmarks

Predator converts **real low-rated outcomes** into new adversarial holdout tests:

```bash
node scripts/forge-apex.mjs predator
```

The process is intentionally two-stage:

1. A benchmark designer proposes self-contained, machine-checkable tests based on recurring historical failures.
2. A separate curator rejects ambiguous, subjective, private, hidden-knowledge, or badly specified tests.

Only schema-valid tests accepted by the curator enter:

```text
~/.forge-hydra/predator/active.json
```

The active Predator pool is capped so old adversarial tests rotate out instead of growing without bound.

## Guardian rollback

Guardian protects against benchmark overfitting or a champion that looks strong offline but performs badly in live use.

Check it manually:

```bash
node scripts/forge-apex.mjs health
```

Change the rollback threshold:

```bash
node scripts/forge-apex.mjs health --threshold=5 --min-feedback=3
```

Normal APEX tasks run Guardian automatically before selecting the active cognition config. If enough feedback collected after the latest promotion falls below the threshold, Guardian restores the previous champion and records the rollback in the lineage log.

## State files

FORGE creates these outside the repository:

- `runs.jsonl`: task, judge reports, critiques, verifier results, and final answers.
- `strategies.json`: reusable strategy mutations extracted from completed runs.
- `feedback.jsonl`: human or external outcome ratings.
- `evolution/active.json`: current evolved champion.
- `evolution/previous.json`: rollback candidate.
- `evolution/champion-history.jsonl`: promotion, survival, and rollback lineage.
- `predator/active.json`: curated adversarial holdout tasks derived from failures.
- `predator/history.jsonl`: Predator benchmark creation history.

The system caps learned strategies to the most recent 100 rules and the Predator pool to the most recent 12 tests so context and benchmark size do not grow without bound.

## Runtime controls

| Variable | Meaning |
| --- | --- |
| `FORGE_AGENT` | OpenClaw agent name, default `main` |
| `FORGE_OPENCLAW_BIN` | OpenClaw executable, default `openclaw` |
| `FORGE_LOCAL` | `1` adds `--local`; set `0` to omit it |
| `FORGE_HOME` | Persistent FORGE state directory, default `~/.forge-hydra` |
| `FORGE_CONFIG` | Alternate cognition config JSON |
| `FORGE_CONCURRENCY` | Max specialist calls running at once |
| `FORGE_TIMEOUT_MS` | Per-agent timeout |
| `FORGE_DARWIN_TIMEOUT_MS` | Maximum Darwin/evolution wrapper runtime |

## Mutable cognition

`config/forge-hydra.json` is intentionally outside the model. Roles and judge weights can be edited, versioned, A/B tested, evolved, and reverted without pretending the protected base model changed.

The current architecture now has three independent pressures:

- **internal adversarial pressure** from HYDRA, Meta-HYDRA, red-team, and regression verification;
- **benchmark selection pressure** from train/holdout evolutionary competition;
- **live outcome pressure** from user/external feedback and Guardian rollback.

## Important limitations

Judge, Meta-HYDRA, critic, synthesizer, verifier, mutator, Predator designer, and curator may still be backed by the same underlying model. Role separation and independent sessions reduce some coupling but do not make them genuinely independent brains.

Predator checks are machine-verifiable but generated benchmark specifications can still be imperfect, which is why they pass both schema validation and an independent curator before use. Real-world feedback remains the strongest signal available to the rollback loop.

The next major upgrade is **multi-model cognitive species**: route different HYDRA heads and evaluators through genuinely different model/provider families, then allow provider routing itself to become an evolvable gene. That would attack correlated blind spots rather than merely adding more passes from one underlying model.
