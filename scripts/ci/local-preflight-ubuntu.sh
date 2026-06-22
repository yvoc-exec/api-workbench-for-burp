#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
original_java_home="${JAVA_HOME:-}"
original_path="$PATH"
lock_file="$repo_root/.git/api-workbench-preflight.lock"
lock_acquired=false
lock_token="$(printf '%s-%s-%s-%s' "$(date -u +%Y%m%dT%H%M%SZ)" "$$" "$(hostname)" "$RANDOM")"
summary_rows=()

cd "$repo_root"

acquire_lock() {
  if ! ( set -o noclobber; : > "$lock_file" ) 2>/dev/null; then
    echo "Another Windows/WSL preflight may be running: $lock_file already exists" >&2
    exit 1
  fi

  cat > "$lock_file" <<EOF
token=$lock_token
os=Ubuntu
pid=$$
timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)
hostname=$(hostname)
EOF
  lock_acquired=true
}

show_summary() {
  if [ "${#summary_rows[@]}" -eq 0 ]; then
    return
  fi

  printf '\n| OS | Java | Command | Result |\n'
  printf '|---|---:|---|---:|\n'
  for row in "${summary_rows[@]}"; do
    printf '%s\n' "$row"
  done
  printf '\n'
}

add_summary_row() {
  local java_label="$1"
  local command_label="$2"
  local result="$3"
  summary_rows+=("| Ubuntu | ${java_label} | \`${command_label}\` | ${result} |")
}

require_command() {
  local command_name="$1"
  if ! command -v "$command_name" >/dev/null 2>&1; then
    show_summary
    echo "Required command not found: $command_name" >&2
    exit 1
  fi
}

resolve_java_home() {
  local env_name="$1"
  local value="${!env_name:-}"
  if [ -z "$value" ]; then
    show_summary
    echo "Required environment variable is not set: $env_name" >&2
    exit 1
  fi
  if [ ! -d "$value" ]; then
    show_summary
    echo "$env_name does not exist: $value" >&2
    exit 1
  fi
  printf '%s\n' "$value"
}

detect_java_major() {
  local java_exe="$1"
  local raw_version
  raw_version="$("$java_exe" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version =/ {print $2; exit}')"
  if [[ ! "$raw_version" =~ ^[0-9]+$ ]]; then
    show_summary
    echo "Unable to determine Java major version from $java_exe" >&2
    exit 1
  fi
  printf '%s\n' "$raw_version"
}

run_with_java() {
  local expected_major="$1"
  local java_home="$2"
  local command_label="$3"
  shift 3

  local java_exe="$java_home/bin/java"
  if [ ! -x "$java_exe" ]; then
    add_summary_row "$expected_major" "$command_label" "FAIL"
    show_summary
    echo "java executable not found: $java_exe" >&2
    exit 1
  fi

  "$java_exe" -version
  local actual_major
  actual_major="$(detect_java_major "$java_exe")"
  if [ "$actual_major" != "$expected_major" ]; then
    add_summary_row "$expected_major" "$command_label" "FAIL"
    show_summary
    echo "Expected Java $expected_major at $java_home but detected Java $actual_major" >&2
    exit 1
  fi

  export JAVA_HOME="$java_home"
  export PATH="$JAVA_HOME/bin:$original_path"

  if ! "$@"; then
    add_summary_row "$expected_major" "$command_label" "FAIL"
    show_summary
    echo "$command_label failed under Java $expected_major" >&2
    exit 1
  fi

  add_summary_row "$expected_major" "$command_label" "PASS"
}

cleanup() {
  if $lock_acquired && [ -f "$lock_file" ]; then
    current_token="$(sed -n 's/^token=//p' "$lock_file" 2>/dev/null | head -n 1)"
    if [ "$current_token" = "$lock_token" ]; then
      rm -f "$lock_file"
    fi
  fi
  export JAVA_HOME="$original_java_home"
  export PATH="$original_path"
}
trap cleanup EXIT

acquire_lock

require_command mvn
require_command xvfb-run
require_command xdpyinfo

java17_home="$(resolve_java_home JAVA17_HOME)"
java21_home="$(resolve_java_home JAVA21_HOME)"
java25_home="$(resolve_java_home JAVA25_HOME)"

run_with_java 17 "$java17_home" "mvn -B clean verify" mvn -B clean verify
run_with_java 21 "$java21_home" "mvn -B clean verify" mvn -B clean verify
run_with_java 25 "$java25_home" "mvn -B clean verify" mvn -B clean verify

run_with_java 17 "$java17_home" 'xvfb-run -a -s "-screen 0 1920x1080x24" mvn -B clean verify -Pui-tests' \
  xvfb-run -a -s "-screen 0 1920x1080x24" mvn -B clean verify -Pui-tests

run_with_java 17 "$java17_home" "mvn -B clean verify -Pstatic-analysis" mvn -B clean verify -Pstatic-analysis

show_summary
