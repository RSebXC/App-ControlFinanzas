#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
kotlinc app/src/main/java/gt/finanzas/privadas/BankParser.kt tests/ParserChecks.kt -include-runtime -d "$TMP_DIR/checks.jar"
java -jar "$TMP_DIR/checks.jar"
