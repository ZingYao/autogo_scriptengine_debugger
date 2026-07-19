#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$repo_root/vscode/extensions/autogo"
npm test
npm run build

cd "$repo_root/jetbrains/extensions/autogo"
if [[ "$(uname -s)" == "Darwin" ]] && /usr/libexec/java_home -v 21 >/dev/null 2>&1; then
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi
./gradlew test buildPlugin
