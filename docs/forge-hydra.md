# APEX FORGE / HYDRA

FORGE is an experimental orchestration layer for NemoClaw/OpenClaw. It does **not** rewrite model weights. It improves the surrounding reasoning process by running competing specialist strategies, auditing the judge, red-teaming the winner, regression-testing the refinement, and carrying reusable strategy rules into later runs.

## What the first version does

One task becomes this pipeline:

1. Spawn independent specialist candidates from `config/forge-hydra.json`.
2. Judge every candidate with a weighted rubric.
3. Run **Meta-HYDRA** to audit the judge and override a bad selection.
4. Red-team the selected winner.
5. Synthesize a repaired answer.
6. Run a regression verifier that compares the repaired answer with the original winner and rejects the repair if it became worse.
7. Extract up to three reusable reasoning mutations.
8. Store run history, mutations, and optional human feedback under `~/.forge-hydra/`.
9. Feed learned strategy rules and recent external feedback into later runs.

This gives the system a mutable cognitive layer even when the underlying model itself is fixed.

## Run it

Inside a NemoClaw/OpenClaw environment:

```bash
node scripts/forge-hydra.mjs "Design a better memory architecture for this agent"
```

Change how many specialist heads participate:

```bash
node scripts/forge-hydra.mjs "your task" --agents=4
```

The command prints the final answer to stdout. Progress and the generated `runId` are printed to stderr.

## Give outcome feedback

After testing an answer in the real world, feed the result back into FORGE:

```bash
node scripts/forge-hydra.mjs feedback RUN_ID 9 "Worked, but the setup step was incomplete"
```

Recent feedback is included in later runs. That matters because self-evaluation alone can reinforce its own mistakes; external outcomes give the loop an independent signal.

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

## Mutable cognition

`config/forge-hydra.json` is intentionally outside the model. Roles and judge weights can be edited, versioned, A/B tested, and reverted through Git.

A future evolutionary controller can create mutations of this file on separate branches, benchmark each mutation, and retain only configurations that outperform the parent on held-out tests.

## State files

FORGE creates these outside the repository:

- `runs.jsonl`: task, judge reports, critiques, verifier results, and final answers.
- `strategies.json`: reusable strategy mutations extracted from completed runs.
- `feedback.jsonl`: human or external outcome ratings.

The system caps learned strategies to the most recent 100 rules so the context does not grow without bound.

## Important limitation

This version's judge, Meta-HYDRA, critic, synthesizer, verifier, and mutator may still be backed by the same underlying model. Diversity comes from role separation, independent contexts, adversarial objectives, and external feedback, not from pretending they are genuinely independent brains.

The strongest next upgrade is an evaluator backed by **different models plus objective benchmark checks**. That would reduce correlated blind spots and make selection pressure more real.
