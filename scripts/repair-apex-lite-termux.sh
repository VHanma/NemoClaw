#!/usr/bin/env bash
set -eu

ROOT="${APEX_LITE_HOME:-$HOME/.apex-lite}"
REPO="$ROOT/NemoClaw"
PROVIDER="$ROOT/provider.env"
BIN_DIR="${PREFIX:-$HOME/.local}/bin"

mkdir -p "$ROOT"
mkdir -p "$BIN_DIR"

if [ ! -d "$REPO/.git" ]; then
  echo "APEX Lite repo missing at $REPO" >&2
  exit 1
fi

git -C "$REPO" fetch origin main
git -C "$REPO" checkout main
git -C "$REPO" pull --ff-only origin main

chmod +x "$REPO/scripts/apex-lite.mjs"

cat > "$BIN_DIR/apex" <<EOF2
#!/usr/bin/env bash
export APEX_LITE_HOME="$ROOT"
exec node "$REPO/scripts/apex-lite.mjs" "\$@"
EOF2
chmod +x "$BIN_DIR/apex"
ln -sf "$BIN_DIR/apex" "$BIN_DIR/apex-lite"

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
printf '\nRepair complete.\n'
"$BIN_DIR/apex" providers
