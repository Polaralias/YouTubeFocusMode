#!/usr/bin/env sh
DIR="$(cd "$(dirname "$0")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA_BIN" -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"