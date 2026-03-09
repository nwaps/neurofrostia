#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_POM="$SCRIPT_DIR/pom.xml"

# Bump the parent pom patch version.
# versions:set propagates the new version to all child <parent><version> blocks automatically.
bump_patch() {
    local current
    current=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout -f "$PARENT_POM")
    local major minor patch
    IFS='.' read -r major minor patch <<< "$current"
    local new_version="$major.$minor.$((patch + 1))"
    mvn versions:set -DnewVersion="$new_version" -DgenerateBackupPoms=false -q -f "$PARENT_POM"
    echo "$new_version"
}

echo "==> Bumping version..."
VERSION=$(bump_patch)
echo "    -> $VERSION"

echo "==> Building all modules..."
mvn install -f "$PARENT_POM" -q

echo "==> Replacing jars in $SCRIPT_DIR..."
rm -f "$SCRIPT_DIR"/CoreLife-*.jar
rm -f "$SCRIPT_DIR"/MoralityEngine-*.jar
cp "$SCRIPT_DIR/CoreLife/target/CoreLife-${VERSION}.jar" "$SCRIPT_DIR/"
cp "$SCRIPT_DIR/MoralityEngine/target/MoralityEngine-${VERSION}.jar" "$SCRIPT_DIR/"

echo "==> Done."
echo "    $SCRIPT_DIR/CoreLife-${VERSION}.jar"
echo "    $SCRIPT_DIR/MoralityEngine-${VERSION}.jar"
