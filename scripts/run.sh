#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
local_jdk="${XDG_DATA_HOME:-$HOME/.local/share}/vaultcore/jdk-21"
java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if [[ -z "${JAVA_HOME:-}" && -x "$local_jdk/bin/java" ]]; then
    java_command="$local_jdk/bin/java"
fi
cd "$project_dir"
shopt -s nullglob
artifacts=(target/vaultcore-*.jar)
if [[ ${#artifacts[@]} -ne 1 ]]; then
    printf 'Build first: ./scripts/mvn.sh verify\n' >&2
    exit 1
fi
exec "$java_command" -Xms32m -Xmx192m -XX:MaxMetaspaceSize=160m \
    -XX:ReservedCodeCacheSize=48m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 \
    -jar "${artifacts[0]}" "$@"
