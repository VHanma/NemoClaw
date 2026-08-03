#!/usr/bin/env bash
set -eu

REPO_URL="https://github.com/VHanma/NemoClaw.git"
ROOT="${APEX_LITE_HOME:-$HOME/.apex-lite}"
REPO="$ROOT/NemoClaw"
PROVIDER="$ROOT/provider.env"

mkdir -p "$ROOT"

if command -v pkg >/dev/null 2>&1; then
  command -v git >/dev/null 2>&1 || pkg install -y git
  command -v node >/dev/null 2>&1 || pkg install -y nodejs-lts
fi

command -v git >/dev/null 2>&1 || { echo "git is required" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "Node.js is required" >&2; exit 1; }

if [ -d "$REPO/.git" ]; then
  git -C "$REPO" fetch origin main
  git -C "$REPO" checkout main
  git -C "$REPO" pull --ff-only origin main
else
  git clone --depth 1 --branch main "$REPO_URL" "$REPO"
fi

chmod +x "$REPO/scripts/apex-lite.mjs"

if [ -n "${PREFIX:-}" ] && [ -d "$PREFIX/bin" ]; then
  BIN_DIR="$PREFIX/bin"
else
  BIN_DIR="$HOME/.local/bin"
  mkdir -p "$BIN_DIR"
fi

cat > "$BIN_DIR/apex" <<EOF2
#!/usr/bin/env bash
export APEX_LITE_HOME="$ROOT"
exec node "$REPO/scripts/apex-lite.mjs" "\$@"
EOF2
chmod +x "$BIN_DIR/apex"
ln -sf "$BIN_DIR/apex" "$BIN_DIR/apex-lite"

if [ ! -f "$PROVIDER" ]; then
  cat > "$PROVIDER" <<'EOF2'
# APEX Lite talks to any chat-completions-compatible endpoint.
# Fill these values, then run: apex status
APEX_LITE_API_URL=
APEX_LITE_MODEL=
APEX_LITE_API_KEY=

# Optional controls
APEX_LITE_AUTO_SYNC=1
APEX_LITE_ARCHETYPES=8
APEX_LITE_PARALLEL=3
EOF2
  chmod 600 "$PROVIDER"
fi

printf '\nAPEX Lite installed.\n'
printf 'Command: %s\n' "$BIN_DIR/apex"
printf 'Pantheon: %s\n' "$REPO/config/forge-archetypes-extensions.json"
printf 'Character lock: %s\n' "$REPO/config/forge-character-policy.json"
printf 'Provider config: %s\n\n' "$PROVIDER"
printf 'Next: edit provider.env, then run: apex status\n'
printf 'Chat: apex "your message"\n'
