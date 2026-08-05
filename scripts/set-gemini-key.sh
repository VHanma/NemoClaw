#!/usr/bin/env bash
set -eu

ROOT="${APEX_LITE_HOME:-$HOME/.apex-lite}"
PROVIDER="$ROOT/provider.env"
TEST_URL="https://generativelanguage.googleapis.com/v1beta/openai/models/gemini-3.6-flash"

mkdir -p "$ROOT"
command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }

printf 'Paste a NEW Gemini API key from Google AI Studio. It will stay hidden.\n'
IFS= read -r -s -p 'Gemini key: ' KEY
printf '\n'

if [ -z "$KEY" ]; then
  echo 'No key entered.' >&2
  exit 1
fi

TMP="$(mktemp)"
trap 'rm -f "$TMP"; unset KEY' EXIT
STATUS="$(curl -sS -o "$TMP" -w '%{http_code}' "$TEST_URL" \
  -H "Authorization: Bearer $KEY" \
  -H 'Content-Type: application/json' || true)"

if [ "$STATUS" != "200" ]; then
  echo "Gemini rejected this key (HTTP $STATUS). Nothing was saved."
  # Show a short provider error without ever printing the key.
  node -e "const fs=require('fs');let s=fs.readFileSync(process.argv[1],'utf8'); try{const j=JSON.parse(s); console.log(j?.error?.message || s.slice(0,500));}catch{console.log(s.slice(0,500));}" "$TMP" 2>/dev/null || true
  echo 'Create/copy a Gemini API key from: https://aistudio.google.com/app/apikey'
  exit 2
fi

# Preserve all other provider settings, replacing only the Gemini key.
if [ -f "$PROVIDER" ]; then
  grep -v '^APEX_LITE_GEMINI_KEY=' "$PROVIDER" > "$PROVIDER.tmp" || true
else
  : > "$PROVIDER.tmp"
fi
printf 'APEX_LITE_GEMINI_KEY=%s\n' "$KEY" >> "$PROVIDER.tmp"
# Ensure free mode defaults exist if the file was recreated.
grep -q '^APEX_LITE_FREE_ONLY=' "$PROVIDER.tmp" || printf 'APEX_LITE_FREE_ONLY=1\n' >> "$PROVIDER.tmp"
grep -q '^APEX_LITE_SINGLE_CALL=' "$PROVIDER.tmp" || printf 'APEX_LITE_SINGLE_CALL=1\n' >> "$PROVIDER.tmp"
grep -q '^APEX_LITE_PROVIDER=' "$PROVIDER.tmp" || printf 'APEX_LITE_PROVIDER=auto\n' >> "$PROVIDER.tmp"
mv "$PROVIDER.tmp" "$PROVIDER"
chmod 600 "$PROVIDER"
unset KEY

echo 'Gemini key verified and saved.'
if command -v apex >/dev/null 2>&1; then
  apex providers
fi
