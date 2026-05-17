#!/bin/bash
set -e

GRADLE_VERSION=8.10
GRADLE_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR=$(mktemp -d)

cleanup() { rm -rf "$TEMP_DIR"; }
trap cleanup EXIT

echo "Downloading Gradle ${GRADLE_VERSION}..."
curl -fsSL -o "${TEMP_DIR}/gradle.zip" "${GRADLE_URL}"

echo "Extracting..."
unzip -q "${TEMP_DIR}/gradle.zip" -d "${TEMP_DIR}"

echo "Generating Gradle wrapper..."
cd "$SCRIPT_DIR"
"${TEMP_DIR}/gradle-${GRADLE_VERSION}/bin/gradle" wrapper --gradle-version "${GRADLE_VERSION}"

echo "Done. Run: ./gradlew check"
