#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
# Works even on JDK installations that omit the javac launcher but include its module.
java --add-modules jdk.compiler com.sun.tools.javac.Main --release 8 -d "$OUT" \
  Bcore/src/main/java/top/niunaijun/blackbox/script/ModuleFiles.java \
  tests/NativeModuleFilesTest.java
java -cp "$OUT" NativeModuleFilesTest "$@"
python3 tests/test_native_module_launcher.py
