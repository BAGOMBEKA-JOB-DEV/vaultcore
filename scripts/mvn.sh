#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
local_jdk="${XDG_DATA_HOME:-$HOME/.local/share}/vaultcore/jdk-21"
if [[ -z "${JAVA_HOME:-}" && -x "$local_jdk/bin/java" ]]; then
    export JAVA_HOME="$local_jdk"
fi
cd "$project_dir"
exec ./mvnw "$@"
