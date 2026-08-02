# APEX Continuity

APEX Continuity keeps one logical conversation alive without allowing the model prompt to grow forever.

## Behavior

Each Pantheon conversation has a stable chat ID. The default is `default`; callers may supply `--chat=<name>` or set `FORGE_CHAT_ID`.

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

This prevents the FORGE/OpenClaw stack from depending on one ever-growing model context window. It does not change limits imposed by an external chat client or provider itself. If a provider has a finite context window, APEX stays under it by using rolling memory instead of replaying the entire transcript.
