#!/usr/bin/env sh
set -eu
mkdir -p build/test-classes
javac -d build/test-classes $(find src/main/java src/test/java -name '*.java' | sort)
java -cp build/test-classes example.property.service.PropertyJobServiceTest
