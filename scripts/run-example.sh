#!/usr/bin/env sh
set -eu
mkdir -p build/classes
javac -d build/classes $(find src/main/java -name '*.java' | sort)
echo 'Compiled. Set INFRAI_API_KEY, then run: java -cp build/classes example.property.PropertyJobApplication'
