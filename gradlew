#!/bin/bash

set -e

# Check if gradle is installed
if command -v gradle >/dev/null 2>&1; then
    gradle "$@"
else
    # Check if gradle wrapper exists
    if [ -f "./gradle/wrapper/gradle-wrapper.jar" ]; then
        export GRADLE_HOME="$(dirname "$(dirname "$(realpath ./gradle/wrapper/gradle-wrapper.jar)")")"
        java -Djarmode=jar -jar ./gradle/wrapper/gradle-wrapper.jar "$@"
    else
        echo "Error: Gradle not found. Please install Gradle or run 'gradle wrapper' to generate the wrapper files."
        exit 1
    fi
fi
