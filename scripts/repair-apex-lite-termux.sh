#!/usr/bin/env bash
set -eu

REPO_URL="https://github.com/VHanma/NemoClaw.git"
ROOT="${APEX_LITE_HOME:-$HOME/.apex-lite}"
REPO="$ROOT/NemoClaw"
PROVIDER="$ROOT/provider.env"
STASHED=0
STASH_NAME=""

if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ]; then
  BIN_DIR="$PREFIX/bin"
else
  BIN_DIR="$HOME/.local/bin"
fi

mkdir -p "$ROOT" "$BIN_DIR"

# Self-heal prerequisites on Termux.
if command -v pkg >/dev/null 2>&1; then
  command -v git >/dev/null 2>&1 || pkg install -y git
  command -v node >/dev/null 2>&1 || pkg install -y nodejs-lts
fi
command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "Node.js is required" >&2; exit 1; }

# Repair from zero if the checkout vanished.
if [ ! -d "$REPO/.git" ]; then
  echo "APEX Lite repo missing. Rebuilding it now..."
  rm -rf "$REPO"
  git clone --depth 1 --branch main "$REPO_URL" "$REPO"
else
  # Preserve any local edits before updating. Do not reapply them automatically,
  # because stale edits to the runtime can break the repaired version again.
  if [ -n "$(git -C "$REPO" status --porcelain)" ]; then
    STASH_NAME="apex-lite-auto-repair-$(date +%Y%m%d-%H%M%S)"
    echo "Local NemoClaw edits detected. Saving them safely as: $STASH_NAME"
    git -C "$REPO" stash push -u -m "$STASH_NAME" >/dev/null
    STASHED=1
  fi

  git -C "$REPO" fetch origin main
  git -C "$REPO" checkout main
  git -C "$REPO" pull --ff-only origin main
fi

chmod +x "$REPO/scripts/apex-lite.mjs"

# Rebuild the global Termux launcher so it always points at the repaired checkout.
cat > "$BIN_DIR/apex" <<EOF2
#!/usr/bin/env bash
export APEX_LITE_HOME="$ROOT"
exec node "$REPO/scripts/apex-lite.mjs" "\$@"
EOF2
chmod +x "$BIN_DIR/apex"
ln -sf "$BIN_DIR/apex" "$BIN_DIR/apex-lite"

# Never overwrite an existing provider file or its keys. Recreate only when missing.
if [ ! -f "$PROVIDER" ]; then
  cat > "$PROVIDER" <<'EOF2'
# APEX Lite zero-dollar provider stack
APEX_LITE_GEMINI_KEY=
APEX_LITE_OPENROUTER_KEY=
APEX_LITE_GROQ_KEY=
APEX_LITE_GITHUB_TOKEN=

APEX_LITE_FREE_ONLY=1
APEX_LITE_SINGLE_CALL=1
APEX_LITE_PROVIDER=auto

# Optional custom provider fallback, ignored while FREE_ONLY=1
APEX_LITE_API_URL=
APEX_LITE_MODEL=
APEX_LITE_API_KEY=

APEX_LITE_AUTO_SYNC=1
APEX_LITE_ARCHETYPES=8
APEX_LITE_PARALLEL=3
EOF2
fi
chmod 600 "$PROVIDER"

"$BIN_DIR/apex" free
printf '\nAPEX Lite repair complete.\n'
printf 'Repo: %s\n' "$REPO"
printf 'Provider file: %s\n' "$PROVIDER"
printf 'Launcher: %s\n' "$BIN_DIR/apex"
if [ "$STASHED" -eq 1 ]; then
  printf 'Saved old local edits in git stash: %s\n' "$STASH_NAME"
fi
printf '\n'
"$BIN_DIR/apex" providers
