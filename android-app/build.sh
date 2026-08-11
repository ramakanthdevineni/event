#!/bin/sh
#
# Simple entry used inside the Android build container.
# Host builds should use Android Studio or a generated Gradle wrapper.
set -e
cd "$(dirname "$0")"
if [ -x "./gradlew" ] && [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  exec ./gradlew "$@"
fi
exec gradle "$@"
