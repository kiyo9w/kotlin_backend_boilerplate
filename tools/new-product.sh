#!/usr/bin/env bash
#
# Stamp a new product from this template.
#
#   tools/new-product.sh --name "My Product" --package com.acme.myproduct DIR
#
# Rewrites the project name, the package, the lowercase project token used for
# containers and volumes, and the environment-key prefix. Refuses to overwrite a
# non-empty directory. Runs the new product's tests when done.

set -euo pipefail

TEMPLATE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NAME=""
PACKAGE=""
ENV_PREFIX=""
TARGET=""

usage() {
  sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --name) NAME="${2:-}"; shift 2 ;;
    --package) PACKAGE="${2:-}"; shift 2 ;;
    --env-prefix) ENV_PREFIX="${2:-}"; shift 2 ;;
    -h|--help) usage 0 ;;
    -*) echo "unknown option: $1" >&2; usage 1 ;;
    *) TARGET="$1"; shift ;;
  esac
done

[[ -n "$NAME" ]] || { echo "--name is required" >&2; usage 1; }
[[ -n "$PACKAGE" ]] || { echo "--package is required" >&2; usage 1; }
[[ -n "$TARGET" ]] || { echo "target directory is required" >&2; usage 1; }

[[ "$PACKAGE" =~ ^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$ ]] \
  || { echo "--package must be a lowercase dotted package, e.g. com.acme.myproduct" >&2; exit 1; }

SLUG="$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+//; s/-+$//')"
[[ -n "$SLUG" ]] || { echo "could not derive a project slug from '$NAME'" >&2; exit 1; }
ENV_PREFIX="${ENV_PREFIX:-$(printf '%s' "$SLUG" | tr '[:lower:]-' '[:upper:]_')_}"

if [[ -e "$TARGET" && -n "$(ls -A "$TARGET" 2>/dev/null)" ]]; then
  echo "refusing to overwrite non-empty directory: $TARGET" >&2
  exit 1
fi
mkdir -p "$TARGET"
TARGET="$(cd "$TARGET" && pwd)"

echo "stamping '$NAME'"
echo "  package:    $PACKAGE"
echo "  slug:       $SLUG"
echo "  env prefix: $ENV_PREFIX"
echo "  target:     $TARGET"

if command -v rsync >/dev/null 2>&1; then
  rsync -a \
    --exclude '.git' --exclude '.gradle' --exclude '.gradle-home' \
    --exclude 'build' --exclude '**/build' --exclude 'deploy/.env' \
    "$TEMPLATE_DIR"/ "$TARGET"/
else
  (cd "$TEMPLATE_DIR" && tar --exclude='.git' --exclude='.gradle' \
    --exclude='.gradle-home' --exclude='build' --exclude='deploy/.env' -cf - .) \
    | (cd "$TARGET" && tar -xf -)
fi

# Rewrite file contents. Order matters: the dotted package before the token.
find "$TARGET" -type f \
  \( -name '*.kt' -o -name '*.kts' -o -name '*.md' -o -name '*.toml' -o -name '*.yml' \
     -o -name '*.yaml' -o -name '*.js' -o -name '*.conf' -o -name '*.sh' \
     -o -name '*.example' -o -name 'Dockerfile' -o -name '.gitignore' -o -name '*.sql' \) \
  -print0 | xargs -0 perl -pi -e "
    s/\bcom\.example\b/$PACKAGE/g;
    s/\bQOLOA_/${ENV_PREFIX}/g;
    s/\bqoloa\b/$SLUG/g;
    s/\bkotlin_backend_boilerplate\b/$SLUG/g;
  " 2>/dev/null || true

# Move the package directory tree to match.
SRC_PKG_DIR="$TARGET/server/src/main/kotlin/com/example"
DST_PKG_DIR="$TARGET/server/src/main/kotlin/$(printf '%s' "$PACKAGE" | tr '.' '/')"
if [[ -d "$SRC_PKG_DIR" ]]; then
  mkdir -p "$(dirname "$DST_PKG_DIR")"
  mv "$SRC_PKG_DIR" "$DST_PKG_DIR"
fi
SRC_TEST_PKG_DIR="$TARGET/server/src/test/kotlin/com/example"
DST_TEST_PKG_DIR="$TARGET/server/src/test/kotlin/$(printf '%s' "$PACKAGE" | tr '.' '/')"
if [[ -d "$SRC_TEST_PKG_DIR" ]]; then
  mkdir -p "$(dirname "$DST_TEST_PKG_DIR")"
  mv "$SRC_TEST_PKG_DIR" "$DST_TEST_PKG_DIR"
fi

# mainClass in the build file.
perl -pi -e "s/com\.example\.server\.ApplicationKt/$PACKAGE.server.ApplicationKt/g" \
  "$TARGET/server/build.gradle.kts"

# Project name in settings.
perl -pi -e "s/rootProject\.name = \".*\"/rootProject.name = \"$SLUG\"/" \
  "$TARGET/settings.gradle.kts"

echo "done. next:"
echo "  cd $TARGET && export GRADLE_USER_HOME=\"\$PWD/.gradle-home\" && ./gradlew :server:test"
