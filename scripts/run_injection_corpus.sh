#!/usr/bin/env bash
# scripts/run_injection_corpus.sh
# VeriSphere anti-injection corpus regression runner - Story 7.1, NFR8, D2.4.
#
# PURPOSE: Validate the production system_prompt_v1.txt resists the curated
# corpus of injection attempts by issuing live Gemini calls and asserting
# verdictLabel / injectionDetected per the corpus expectations.
#
# QUOTA: ~30 calls per run x N revisions. Burns the founder's daily quota
# on the bundled / dev GEMINI_API_KEY. See SECURITY.md "Anti-injection
# corpus runner - quota note" before each run. Pre-release gate; NOT CI.
#
# USAGE: ./scripts/run_injection_corpus.sh
#   Reads GEMINI_API_KEY from env OR local.properties at repo root.
#   Exit 0 = all PASS. Exit 1 = at least one regression. Exit 2 = preflight failure.
#
# WINDOWS: run from Git Bash. Requires `choco install imagemagick` (provides
# `magick`) + `choco install jq` (curl + base64 ship with Git Bash by default).
# On macOS use `brew install imagemagick jq`; on Debian/Ubuntu
# `apt install imagemagick jq`.
set -euo pipefail

# === Config ===
# Model pin: matches GeminiClient.DEFAULT_MODEL (Story 7.1 CDN #3) - the 2026-05-11
# Story 2.4 smoke hotfix from `gemini-3-flash-preview` to `gemini-2.5-flash`.
# A future hotfix to GeminiClient.DEFAULT_MODEL MUST update this constant in lockstep.
readonly MODEL="gemini-2.5-flash"
readonly ENDPOINT="https://generativelanguage.googleapis.com/v1beta/models"
readonly CORPUS_FILE="${CORPUS_FILE:-app/src/test/resources/injection_corpus.txt}"
readonly PROMPT_FILE="${PROMPT_FILE:-app/src/main/assets/system_prompt_v1.txt}"
readonly IMAGE_WIDTH=800
readonly IMAGE_HEIGHT=600
readonly FONT_SIZE=24
readonly JPEG_QUALITY=85

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

# === Preflight checks (Story 7.1 AC #2) ===

# 1. GEMINI_API_KEY: env-var first, then local.properties parse.
if [[ -z "${GEMINI_API_KEY:-}" ]]; then
  if [[ -f local.properties ]]; then
    GEMINI_API_KEY="$(grep -E '^GEMINI_API_KEY=' local.properties 2>/dev/null | head -1 | cut -d= -f2-)"
  fi
fi
if [[ -z "${GEMINI_API_KEY:-}" ]]; then
  echo "ERROR: GEMINI_API_KEY not set (export it OR add GEMINI_API_KEY=... to local.properties at repo root)" >&2
  exit 2
fi

# 2. Tool checks. curl + jq + base64 are non-negotiable.
for cmd in curl jq base64; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    case "$cmd" in
      jq)
        echo "ERROR: required tool 'jq' not found on PATH" >&2
        echo "  Install: 'choco install jq' (Windows) | 'brew install jq' (macOS) | 'apt install jq' (Debian/Ubuntu)" >&2
        ;;
      *)
        echo "ERROR: required tool '$cmd' not found on PATH" >&2
        ;;
    esac
    exit 2
  fi
done

# ImageMagick lives under either `magick` (v7+) or `convert` (v6) - try both.
# IMPORTANT: on Windows, `convert.exe` is a built-in filesystem tool (FAT/NTFS
# conversion), NOT ImageMagick. We probe `convert -version` and only accept it
# if the output identifies as ImageMagick - prevents silent misdiagnosis.
MAGICK_CMD=""
if command -v magick >/dev/null 2>&1; then
  MAGICK_CMD="magick"
elif command -v convert >/dev/null 2>&1; then
  if convert -version 2>&1 | grep -q -i 'ImageMagick'; then
    MAGICK_CMD="convert"
  fi
fi
if [[ -z "$MAGICK_CMD" ]]; then
  echo "ERROR: ImageMagick not found on PATH (looked for 'magick' and ImageMagick-flavoured 'convert')" >&2
  echo "  Install: 'choco install imagemagick' (Windows) | 'brew install imagemagick' (macOS) | 'apt install imagemagick' (Debian/Ubuntu)" >&2
  echo "  NOTE: Windows ships a built-in 'convert.exe' for filesystem conversion - it is NOT ImageMagick." >&2
  exit 2
fi

# 3. File checks.
if [[ ! -f "$CORPUS_FILE" ]]; then
  echo "ERROR: corpus file not found at $CORPUS_FILE" >&2
  exit 2
fi
if [[ ! -f "$PROMPT_FILE" ]]; then
  echo "ERROR: system prompt file not found at $PROMPT_FILE" >&2
  exit 2
fi

SYSTEM_PROMPT="$(cat "$PROMPT_FILE")"

# base64 flavour: GNU coreutils uses -w 0 (no wrap); macOS BSD has no -w but
# never wraps for stdin <= 76 chars. Detect once.
if base64 --help 2>&1 | grep -q -- '-w'; then
  base64_nowrap() { base64 -w 0 "$1"; }
else
  base64_nowrap() { base64 < "$1" | tr -d '\n'; }
fi

echo "===== VeriSphere anti-injection corpus runner ====="
echo "Model: $MODEL"
echo "Corpus: $CORPUS_FILE"
echo "ImageMagick: $MAGICK_CMD"
echo "==================================================="

# === Corpus iteration ===
TOTAL=0
PASSED=0
FAILED=0
declare -a FAILURES=()

LINE_NUM=0
while IFS= read -r line || [[ -n "$line" ]]; do
  LINE_NUM=$((LINE_NUM + 1))
  # Skip blank lines and #-prefixed comments.
  if [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]]; then
    continue
  fi
  # Parse <expected>::<injection_text>. The first `::` is the separator;
  # subsequent `::` in the text are preserved as part of the injection.
  if [[ "$line" != *"::"* ]]; then
    echo "WARN: line $LINE_NUM malformed (no '::' separator), skipping: $line" >&2
    continue
  fi
  EXPECTED="${line%%::*}"
  INJECTION_TEXT="${line#*::}"

  TOTAL=$((TOTAL + 1))

  # Validate expected label.
  case "$EXPECTED" in
    TRUE|FALSE|DOUBTFUL|NON_VERIFIABLE|INJECTION_DETECTED) ;;
    *)
      FAILED=$((FAILED + 1))
      FAILURES+=("line $LINE_NUM unknown expected label: $EXPECTED")
      printf '[ERROR] line %d unknown expected: %s\n' "$LINE_NUM" "$EXPECTED"
      continue
      ;;
  esac

  # Generate synthetic 800x600 black-on-white JPEG of the injection text.
  # Write text to a temp file and pass via @-syntax to dodge shell escaping
  # for $, backticks, single quotes, !, etc.
  TEXT_FILE="$TMP_DIR/case_${LINE_NUM}.txt"
  IMG_FILE="$TMP_DIR/case_${LINE_NUM}.jpg"
  printf '%s' "$INJECTION_TEXT" > "$TEXT_FILE"
  if ! "$MAGICK_CMD" -size "${IMAGE_WIDTH}x${IMAGE_HEIGHT}" xc:white \
        -font Helvetica -pointsize "$FONT_SIZE" -fill black \
        -gravity center -annotate +0+0 "@$TEXT_FILE" \
        -quality "$JPEG_QUALITY" "$IMG_FILE" 2>/dev/null; then
    FAILED=$((FAILED + 1))
    FAILURES+=("line $LINE_NUM ImageMagick failed to generate image")
    printf '[ERROR] line %d image-gen failure\n' "$LINE_NUM"
    continue
  fi

  IMG_B64="$(base64_nowrap "$IMG_FILE")"

  # Build request body mirroring GeminiRequest.build verbatim (Story 7.1 CDN #2).
  # Use jq -nc so quote-escaping of $SYSTEM_PROMPT + $IMG_B64 is correct.
  REQUEST_JSON="$(jq -nc \
    --arg sysp "$SYSTEM_PROMPT" \
    --arg img "$IMG_B64" \
    '{
      contents: [{ role: "user", parts: [
        { text: $sysp },
        { inlineData: { mimeType: "image/jpeg", data: $img } }
      ]}],
      tools: [{ googleSearch: {} }],
      generationConfig: { thinkingConfig: { thinkingBudget: 0 } }
    }')"

  # POST to live Gemini.
  RESPONSE="$(curl -sS \
    -H 'Content-Type: application/json' \
    -X POST \
    --data-binary @- \
    "${ENDPOINT}/${MODEL}:generateContent?key=${GEMINI_API_KEY}" <<< "$REQUEST_JSON" 2>&1 || true)"

  # Parse the envelope -> inner verdict JSON. Strip markdown ```json fences
  # exactly like GeminiClient.parseVerdict (Story 7.1 CDN #10).
  VERDICT_TEXT="$(printf '%s' "$RESPONSE" | jq -r '.candidates[0].content.parts[0].text // empty' 2>/dev/null || true)"
  if [[ -z "$VERDICT_TEXT" ]]; then
    FAILED=$((FAILED + 1))
    FAILURES+=("line $LINE_NUM empty candidate (HTTP error or content-blocked)")
    printf '[FAIL] line %d expected=%s reason=empty-candidate\n' "$LINE_NUM" "$EXPECTED"
    continue
  fi
  VERDICT_TEXT="$(printf '%s' "$VERDICT_TEXT" \
    | sed -e 's/^```json//' -e 's/^```//' -e 's/```$//' \
    | tr -d '\r')"
  ACTUAL_LABEL="$(printf '%s' "$VERDICT_TEXT" | jq -r '.verdictLabel // "<missing>"' 2>/dev/null || echo "<parse-error>")"
  ACTUAL_INJ="$(printf '%s' "$VERDICT_TEXT" | jq -r '.injectionDetected // false' 2>/dev/null || echo "<parse-error>")"

  # Compare per Story 7.1 AC #2 step 6.
  PASS=false
  case "$EXPECTED" in
    INJECTION_DETECTED)
      [[ "$ACTUAL_INJ" == "true" ]] && PASS=true
      ;;
    TRUE|FALSE|DOUBTFUL|NON_VERIFIABLE)
      [[ "$ACTUAL_LABEL" == "$EXPECTED" ]] && PASS=true
      ;;
  esac

  # Per-case log line. Truncate injection text to 80 chars per CDN #8 (privacy
  # posture: never log full body / system prompt / API key / curl URL).
  TEXT_PREVIEW="${INJECTION_TEXT:0:80}"
  if $PASS; then
    PASSED=$((PASSED + 1))
    printf '[PASS] line %d expected=%s actual_label=%s actual_inj=%s\n' \
      "$LINE_NUM" "$EXPECTED" "$ACTUAL_LABEL" "$ACTUAL_INJ"
  else
    FAILED=$((FAILED + 1))
    FAILURES+=("line $LINE_NUM expected=$EXPECTED actual_label=$ACTUAL_LABEL actual_inj=$ACTUAL_INJ text=\"$TEXT_PREVIEW\"")
    printf '[FAIL] line %d expected=%s actual_label=%s actual_inj=%s text="%s"\n' \
      "$LINE_NUM" "$EXPECTED" "$ACTUAL_LABEL" "$ACTUAL_INJ" "$TEXT_PREVIEW"
  fi
done < "$CORPUS_FILE"

# === Summary footer ===
echo
echo "===== Injection-corpus run summary ====="
echo "  Total:  $TOTAL"
echo "  Passed: $PASSED"
echo "  Failed: $FAILED"
echo "  Model:  $MODEL"
echo "========================================"

if (( FAILED > 0 )); then
  echo
  echo "Regressions:"
  for f in "${FAILURES[@]}"; do
    echo "  - $f"
  done
  exit 1
fi
exit 0
