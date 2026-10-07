#!/bin/sh
# Builds build/kronwerke-launcher.jar with nothing but a JDK (21 or newer).
# Usage: sh build.sh [version]
set -eu
cd "$(dirname "$0")"
version="${1:-dev}"
rm -rf build && mkdir -p build/classes build/test
javac --release 21 -Xlint:all -Werror -d build/classes $(find src/main/java -name '*.java')
printf 'Main-Class: de.kronwerke.boot.Boot\nImplementation-Title: Kronwerke launcher\nImplementation-Version: %s\n' "$version" > build/MANIFEST.MF
[ -d src/main/resources ] && cp -r src/main/resources/. build/classes/
jar --create --file build/kronwerke-launcher.jar --manifest build/MANIFEST.MF -C build/classes .
javac --release 21 -cp build/classes -d build/test $(find src/test/java -name '*.java')
java -cp build/classes:build/test de.kronwerke.launcher.Tests
echo "built build/kronwerke-launcher.jar ($version)"
