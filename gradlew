#!/usr/bin/env sh
set -e
DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
GRADLE_USER_HOME="$DIR/.gradle" gradle "$@"
