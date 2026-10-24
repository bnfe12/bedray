#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
test_classes="$(mktemp -d)"
trap 'rm -rf "$test_classes"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -d "$test_classes" \
  "$repo_dir"/renderer-api/src/main/java/io/github/andrexarlay/bedray/api/v1/{CameraSnapshot,SectionSnapshot,WorldSnapshot,WorldSnapshotStream}.java \
  "$repo_dir"/compute-common/src/main/java/io/github/andrexarlay/bedray/compute/voxel/VoxelWorldCache.java \
  "$repo_dir"/tools/tests/WorldSnapshotContractTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$test_classes" WorldSnapshotContractTest
