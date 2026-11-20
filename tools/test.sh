#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "${BASH_SOURCE[0]}")/gradle.sh" :core:test :app:testDebugUnitTest "$@"
