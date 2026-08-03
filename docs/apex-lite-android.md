# APEX Lite for Android / Termux

APEX Lite is the phone-sized APEX runtime. It keeps the permanent Pantheon, strict character policy, multi-seat reasoning, King Yujiro final synthesis, Baki as adaptive heir, per-chat continuity, and daily GitHub sync without requiring Docker, OpenShell, or the full NemoClaw sandbox.

## Permanent character rule

`config/forge-character-policy.json` is loaded every launch.

- Every named Pantheon figure stays in character in voice, attitude, priorities, and reasoning style.
- Factual corrections, uncertainty, and evidence limits are expressed in-character.
- Deadpool and Sheogorath are the only intentional fourth-wall exceptions.
- One Pantheon figure cannot impersonate another.
- King Yujiro Hanma delivers the final Court synthesis by default.

## Phone install

From Termux, run the installer from a checked-out copy of this repository:

```bash
bash scripts/install-apex-lite-termux.sh
```

The installer places the phone copy under `~/.apex-lite/NemoClaw` and exposes both `apex` and `apex-lite` commands.

It creates `~/.apex-lite/provider.env`. Configure a chat-completions-compatible model endpoint there:

```text
APEX_LITE_API_URL=
APEX_LITE_MODEL=
APEX_LITE_API_KEY=
```

A local endpoint may omit the API key when it does not require one.

## Commands

```bash
apex status
apex new main
apex "your message"
apex new training
apex use main
apex list
apex current
apex sync
```

The active chat transcript is stored under `~/.apex-lite/chats/<chat-name>/transcript.jsonl`.

## Phone behavior

Each message selects a small high-value subset of the Pantheon. Yujiro and Baki are permanently included. Relevant seats reason in parallel batches, then King Yujiro receives their briefings and delivers the final answer. This preserves the multi-perspective APEX design while keeping phone compute and network use lower than the full NemoClaw stack.

By default APEX Lite checks GitHub for a fast-forward update at most once per day. Set `APEX_LITE_AUTO_SYNC=0` in `provider.env` to disable that behavior.
