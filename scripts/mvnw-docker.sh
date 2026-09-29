#!/usr/bin/env bash
# Runs the API's Maven Wrapper inside a JDK 21 container, for machines without a local JDK.
# Example: scripts/mvnw-docker.sh verify
#
# Testcontainers starts PostgreSQL as a sibling container through the mounted Docker socket and
# reaches its mapped port via host.docker.internal. Maven's cache lives in a named volume.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

exec docker run --rm \
  -v "$repo_root/api":/workspace -w /workspace \
  -v ml-scheduler-m2:/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  --add-host=host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  eclipse-temurin:21.0.12.1_1-jdk-noble \
  ./mvnw -B -ntp "$@"
