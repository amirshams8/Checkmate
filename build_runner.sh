#!/data/data/com.termux/files/usr/bin/bash
#
# build_runner.sh — Autonomous-safe version
#
# Build status contract:
#   /sdcard/ai-automation/build_status.txt  → "SUCCESS" or "FAILURE"
#   /sdcard/ai-automation/build_complete.flag → touched last, always
#
# Android side reads build_status.txt to determine result.
# Folder existence is never used for pass/fail detection.

set -euo pipefail

# ❌ OLD
# REPO_DIR="/data/data/com.termux/files/home/jarvis"

# ✅ NEW (dynamic repo from Android)
REPO_DIR="$(pwd)"
BASE_DIR="/sdcard/ai-automation"
AI_OUTPUT="$BASE_DIR/ai-output.txt"
DOWNLOAD_DIR="/sdcard/Download"
COMPLETE_FLAG="$BASE_DIR/build_complete.flag"
ASSEMBLED_FLAG="$BASE_DIR/downloads_assembled.flag"
SNAPSHOT_FILE="$BASE_DIR/download_snapshot.txt"
STATUS_FILE="$BASE_DIR/build_status.txt"
LOG_DIR="$BASE_DIR/logs"
ERROR_LOG_DIR="$BASE_DIR/build_error_logs"
ERROR_REPORT="$BASE_DIR/build-error-report.txt"

mkdir -p "$LOG_DIR"
exec >> "$LOG_DIR/build_runner.log" 2>&1

echo ""
echo "=== build_runner.sh START $(date) args=${*:-none} ==="

# ─────────────────────────────────────────────────────────────
# MODE: snapshot
# ─────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--snapshot" ]]; then
    echo "[snapshot] Recording existing files"
    rm -f "$SNAPSHOT_FILE"
    find "$DOWNLOAD_DIR" -maxdepth 1 -type f -exec basename {} \; \
        > "$SNAPSHOT_FILE" 2>/dev/null || true
    echo "[snapshot] Done"
    exit 0
fi

# ─────────────────────────────────────────────────────────────
# MODE: assemble downloaded files
# ─────────────────────────────────────────────────────────────
if [[ "${1:-}" == "--assemble-downloads" ]]; then
    echo "[assemble] Starting"

    declare -A BEFORE
    if [[ -f "$SNAPSHOT_FILE" ]]; then
        while IFS= read -r name; do
            BEFORE["$name"]=1
        done < "$SNAPSHOT_FILE"
    fi

    CODE_EXTS="kt java py txt xml json gradle kts sh cpp c h ts js md"

    NEW_FILES=()
    DEADLINE=$(( $(date +%s) + 30 ))

    while (( $(date +%s) < DEADLINE )); do
        while IFS= read -r -d '' f; do
            NAME=$(basename "$f")
            EXT="${NAME##*.}"
            if [[ -z "${BEFORE[$NAME]+_}" ]] && \
               [[ -s "$f" ]] && \
               echo "$CODE_EXTS" | grep -qw "$EXT"; then
                NEW_FILES+=("$f")
            fi
        done < <(find "$DOWNLOAD_DIR" -maxdepth 1 -type f -print0 2>/dev/null)

        [[ ${#NEW_FILES[@]} -gt 0 ]] && break
        sleep 1
    done

    if [[ ${#NEW_FILES[@]} -eq 0 ]]; then
        echo "[assemble] ERROR: No new files"
        exit 1
    fi

    > "$AI_OUTPUT"
    for f in "${NEW_FILES[@]}"; do
        NAME=$(basename "$f")
        printf '//===== FILE: %s =====\n' "$NAME" >> "$AI_OUTPUT"
        cat "$f" >> "$AI_OUTPUT"
        printf '\n\n' >> "$AI_OUTPUT"
    done

    touch "$ASSEMBLED_FLAG"
    rm -f "$SNAPSHOT_FILE"
    echo "[assemble] DONE"
    exit 0
fi

# ─────────────────────────────────────────────────────────────
# MODE: normal build
# ─────────────────────────────────────────────────────────────

# ── Stale state cleanup ───────────────────────────────────────
# Remove all flags and old error logs before starting a new build.
# This ensures Android never reads stale data from a previous iteration.
echo "[cleanup] Removing stale flags and error logs"
rm -f "$COMPLETE_FLAG"
rm -f "$STATUS_FILE"
rm -f "$ERROR_REPORT"
rm -rf "$ERROR_LOG_DIR"
mkdir -p "$ERROR_LOG_DIR"

echo "[normal] Starting build mode"

cd "$REPO_DIR"

# ── Clean repo error logs too ─────────────────────────────────
# Prevent old committed error logs from being re-read as new failures.
echo "[cleanup] Removing old build_error_logs from repo"
rm -rf "$REPO_DIR/build_error_logs"
git add -A build_error_logs 2>/dev/null || true

echo "[sync] Force syncing with origin/main"
git fetch origin main
git reset --hard origin/main
echo "[sync] Repo synced"

echo "[copy] Updating ai-output.txt"
cp "$AI_OUTPUT" "$REPO_DIR/ai-output.txt"

git config user.name  "AutoBuild Bot"
git config user.email "bot@autobuild.local"

git add ai-output.txt
git commit -m "AutoBuild: update ai-output.txt" || echo "[skip] Nothing to commit"
git push origin main || echo "[warn] git push failed"

echo "[wait] Waiting 20s for workflows to queue..."
sleep 20

# ─────────────────────────────────────────────────────────────
# Watch APK workflow — extended polling to avoid false timeouts
# Never re-trigger build on timeout; only wait longer.
# ─────────────────────────────────────────────────────────────

APK_WORKFLOW_NAME="Build APK"
APK_RUN_ID=""
CONCLUSION=""

echo "[watch] Looking for APK workflow run..."
APK_RUN_ID=$(gh run list \
    --workflow="$APK_WORKFLOW_NAME" \
    --limit=1 \
    --json databaseId \
    -q '.[0].databaseId' 2>/dev/null || true)

if [[ -z "${APK_RUN_ID:-}" ]]; then
    echo "[warn] No APK workflow run found — writing FAILURE and exiting"
    echo "FAILURE" > "$STATUS_FILE"
    touch "$COMPLETE_FLAG"
    echo "=== build_runner.sh DONE (no run found) $(date) ==="
    exit 0
fi

echo "[watch] Watching run $APK_RUN_ID (gh run watch)..."
gh run watch "$APK_RUN_ID" --exit-status || true

# ── Deterministic result from gh run view ─────────────────────
# Use conclusion field — never rely on folder existence.
echo "[status] Querying conclusion for run $APK_RUN_ID..."
CONCLUSION=$(gh run view "$APK_RUN_ID" \
    --json conclusion \
    -q '.conclusion' 2>/dev/null || echo "unknown")

echo "[status] Conclusion = $CONCLUSION"

# ─────────────────────────────────────────────────────────────
# Sync repo to fetch error logs if Apk.yml committed them
# ─────────────────────────────────────────────────────────────
echo "[sync] Final git pull to fetch any committed error logs"
git fetch origin main
git reset --hard origin/main

# ─────────────────────────────────────────────────────────────
# Write build_status.txt and copy/clear error logs
# ─────────────────────────────────────────────────────────────
if [[ "$CONCLUSION" == "success" ]]; then
    echo "[result] BUILD SUCCESS"
    echo "SUCCESS" > "$STATUS_FILE"

    # Ensure no stale error logs remain in shared storage
    rm -rf "$ERROR_LOG_DIR"
    mkdir -p "$ERROR_LOG_DIR"
    rm -f "$ERROR_REPORT"
    echo "[result] Cleared error logs from shared storage"

else
    echo "[result] BUILD FAILURE (conclusion=$CONCLUSION)"
    echo "FAILURE" > "$STATUS_FILE"

    # Copy error logs to shared storage if present in repo.
    # Gated on directory existing with ANY files — not just error_summary.txt.
    rm -rf "$ERROR_LOG_DIR"
    mkdir -p "$ERROR_LOG_DIR"
    if [[ -d "$REPO_DIR/build_error_logs" ]] && \
       [[ -n "$(ls -A "$REPO_DIR/build_error_logs" 2>/dev/null)" ]]; then
        cp -f "$REPO_DIR/build_error_logs/"* "$ERROR_LOG_DIR/" 2>/dev/null || true
        echo "[result] Error logs copied to $ERROR_LOG_DIR ($(ls "$ERROR_LOG_DIR" | wc -l) files)"
    else
        echo "[warn] No build_error_logs in repo — report will use build log only"
    fi

    # ── CREATE CONSOLIDATED ERROR REPORT ──────────────────────────
    # Android expects a single file: build-error-report.txt
    # Include everything available: summary, error files, and build tail.
    # build_tail.txt always contains the last ~100 lines of build output
    # and is the most reliable source of actual compiler errors.
    {
        echo "=== BUILD FAILURE REPORT ==="
        echo "Date: $(date)"
        echo "Conclusion: $CONCLUSION"
        echo ""

        if [[ -f "$ERROR_LOG_DIR/error_summary.txt" ]]; then
            echo "=== ERROR SUMMARY ==="
            cat "$ERROR_LOG_DIR/error_summary.txt"
            echo ""
        fi

        if [[ -f "$ERROR_LOG_DIR/error_files.txt" ]]; then
            echo "=== DETAILED ERROR FILES ==="
            cat "$ERROR_LOG_DIR/error_files.txt"
            echo ""
        fi

        if [[ -f "$ERROR_LOG_DIR/error_file_list.txt" ]]; then
            echo "=== FILES WITH ERRORS ==="
            cat "$ERROR_LOG_DIR/error_file_list.txt"
            echo ""
        fi

        if [[ -f "$ERROR_LOG_DIR/build_tail.txt" ]]; then
            echo "=== BUILD OUTPUT (TAIL) ==="
            cat "$ERROR_LOG_DIR/build_tail.txt"
            echo ""
        fi

        # Last resort: if nothing above exists, include last 200 lines of build output
        if [[ ! -f "$ERROR_LOG_DIR/error_summary.txt" ]] && \
           [[ ! -f "$ERROR_LOG_DIR/build_tail.txt" ]]; then
            echo "=== RAW BUILD LOG (last 200 lines) ==="
            if [[ -f "$ERROR_LOG_DIR/build_output.txt" ]]; then
                tail -200 "$ERROR_LOG_DIR/build_output.txt"
            else
                echo "(no build log available)"
            fi
            echo ""
        fi
    } > "$ERROR_REPORT"
    echo "[result] Created consolidated error report: $ERROR_REPORT ($(wc -c < "$ERROR_REPORT") bytes)"
fi

# ─────────────────────────────────────────────────────────────
# ALWAYS touch build_complete.flag last
# Android polls for this flag; build_status.txt tells it the result.
# ─────────────────────────────────────────────────────────────
echo "[final] Touching build_complete.flag"
touch "$COMPLETE_FLAG" || true

echo "=== build_runner.sh DONE (conclusion=$CONCLUSION) $(date) ==="
