<!-- SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved. -->
<!-- SPDX-License-Identifier: Apache-2.0 -->
# APEX Continuity

APEX Continuity keeps one logical conversation alive without allowing the model prompt to grow forever.

## Global upgraded-chat entrypoint

After NemoClaw is linked or installed from this branch, use either:

```bash
apex "your message"
```

or:

```bash
apex-chat "your message"
```

Those launchers force the full APEX stack on by default for chat work:

- Continuity
- Pantheon and archetype councils
- Cognitive Species routing
- HYDRA specialist competition
- Judge + Meta-HYDRA
- Red Team
- synthesis + regression verification
- confidence calibration
- Guardian health/rollback checks
- the currently evolved cognition champion and learned strategy memory

The launcher intentionally does not replace or shadow the real `openclaw` executable because the Cognitive Species router may need that binary as its safe base backend.

## Separate chat identities

Each conversation has its own continuity namespace instead of sharing one giant memory pool.

The universal launcher chooses the chat ID in this order:

1. explicit `--chat=<name>`
2. `FORGE_CHAT_ID`
3. `APEX_CHAT_ID`
4. `OPENCLAW_SESSION_ID`
5. `CHAT_ID` or `SESSION_ID`
6. the currently selected APEX chat
7. `main`

Manage named chats with:

```bash
apex new punch-app
apex use punch-app
apex current
apex list
```

Then normal messages continue inside that logical chat:

```bash
apex "continue fixing the camera detection"
```

## Behavior

For each chat, FORGE stores:

- `~/.forge-hydra/continuity/<chat-id>/transcript.jsonl` — append-only full user/assistant transcript.
- `~/.forge-hydra/continuity/<chat-id>/state.json` — compact rolling memory plus the most recent verbatim turns.

Older turns are never deleted from the transcript archive. When the active recent window grows past the configured turn or character budget, APEX asks a high-priority model species to compress the older working-context material into durable memory, keeps the newest turns verbatim, and continues using the same chat ID.

The compactor is instructed to preserve goals, decisions, corrections, constraints, exact identifiers/numbers/paths/commands when important, failures, unresolved work, and the distinction between fact, inference, speculation, and symbolic interpretation.

The final synthesizer is explicitly told that conversation length is not a reason to ask the user to start a new chat. Context rollover is handled by Continuity.

## Controls

- `FORGE_CHAT_ID` — stable logical conversation ID.
- `--chat=<name>` — per-run override.
- `FORGE_CONTINUITY_RECENT_TURNS` — number of recent transcript entries retained verbatim before compaction, default `12`.
- `FORGE_CONTINUITY_RECENT_CHARS` — recent working-memory character budget, default `26000`.

## Limits

This makes the **APEX/NemoClaw chat path** use the upgraded system by default. Repository code cannot change how unrelated native ChatGPT app conversations are executed by OpenAI's service, and it cannot remove hard limits imposed by an external chat client or provider. Inside APEX, finite provider context windows are handled through rolling memory instead of replaying the entire transcript.
