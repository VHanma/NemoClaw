<!-- SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# FORGE Evolution

`forge-evolve.mjs` adds selection pressure to FORGE/HYDRA. Instead of trusting every self-proposed improvement, it creates cognition variants, benchmarks them, and promotes a variant only if it beats the current champion by a configured margin while protecting held-out worst-case performance.

## Core loop

1. Evaluate the current cognition config on training and holdout tasks.
2. Show only the **training summary** to the mutation designer.
3. Create a population of conservative variants. The first variant can be AI-proposed; the rest are deterministic mutations.
4. Run every variant through the full `forge-hydra.mjs` pipeline.
5. Score answers with machine-checkable benchmark rules.
6. Weight held-out performance more heavily than training performance.
7. Promote only when the best variant clears both the improvement gate and the worst-case regression gate.
8. Write the champion to `~/.forge-hydra/evolution/active.json` and save a full experiment report.

The held-out prompts still exist in the benchmark file, but they are never included in the mutation-design prompt. This prevents the mutation agent from directly tuning its proposed strategy to those answers.

## Run evolution

```bash
node scripts/forge-apex.mjs evolve --generations=2 --population=4
```

Useful controls:

```bash
node scripts/forge-apex.mjs evolve \
  --generations=3 \
  --population=6 \
  --min-delta=0.2 \
  --max-worst-drop=0.5 \
  --agents=6
```

Set `FORGE_EVOLVE_AI=0` to disable the AI-designed mutation and use only deterministic mutations.

## Use the champion

Normal APEX calls automatically prefer the promoted champion if one exists:

```bash
node scripts/forge-apex.mjs "your task"
```

The launcher uses `~/.forge-hydra/evolution/active.json` when present and otherwise falls back to `config/forge-hydra.json`.

## Why this is a real upgrade over self-critique

Self-critique can create a feedback echo where the same model invents a rule, approves the rule, and then treats that rule as learned wisdom. Evolution adds an independent requirement: a cognition change must improve observable benchmark outcomes before it becomes the champion.

The current benchmark is intentionally small and machine-checkable. The next major upgrade is a larger benchmark mix with independent model judges, executable coding tests, retrieval tests, forecasting calibration, adversarial prompt tests, and real user outcome scores.
