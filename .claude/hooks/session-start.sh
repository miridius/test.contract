#!/bin/bash
set -euo pipefail

# Only run in remote (web) environments
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

# Install Clojure CLI if not present
if ! command -v clojure &>/dev/null && ! [ -x "$HOME/.local/bin/clojure" ]; then
  curl -L -O https://github.com/clojure/brew-install/releases/latest/download/linux-install.sh
  chmod +x linux-install.sh
  ./linux-install.sh --prefix "$HOME/.local"
  rm -f linux-install.sh
fi

# Ensure ~/.local/bin is on PATH for this session
if [ -f "$CLAUDE_ENV_FILE" ] 2>/dev/null; then
  echo 'export PATH="$HOME/.local/bin:$PATH"' >> "$CLAUDE_ENV_FILE"
fi
export PATH="$HOME/.local/bin:$PATH"

# Clear JAVA_TOOL_OPTIONS to avoid proxy issues with vendored deps
if [ -f "$CLAUDE_ENV_FILE" ] 2>/dev/null; then
  echo 'export JAVA_TOOL_OPTIONS=""' >> "$CLAUDE_ENV_FILE"
fi
export JAVA_TOOL_OPTIONS=""

# Pre-warm the classpath cache (deps are vendored locally, no network needed)
cd "$CLAUDE_PROJECT_DIR"
clojure -Spath >/dev/null 2>&1 || true
clojure -A:test -Spath >/dev/null 2>&1 || true
