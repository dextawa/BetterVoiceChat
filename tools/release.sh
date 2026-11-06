#!/usr/bin/env bash
# Builds every artifact and collects them into dist/<version>/ with SHA-256 checksums.
#
#   tools/release.sh            build everything, then collect
#   tools/release.sh --collect  only collect what is already built
#
# Needs JDK 21 (and 25 for the 26.x client nodes); Gradle provisions JDK 8 for the legacy clients.
set -euo pipefail
cd "$(dirname "$0")/.."

version=$(sed -n 's/^version=//p' gradle.properties)
for f in client/modern/gradle.properties client/legacy/gradle.properties; do
  v=$(sed -n 's/^mod.version=//p' "$f")
  [ "$v" = "$version" ] || { echo "$f has mod.version=$v but gradle.properties has $version" >&2; exit 1; }
done

if [ "${1:-}" != "--collect" ]; then
  ./gradlew build
  ./gradlew -p client/modern build
  ./gradlew -p client/legacy build
fi

out="dist/$version"
rm -rf "$out"
mkdir -p "$out/proxy" "$out/server" "$out/mods"

cp proxy/velocity/build/libs/BetterVoiceChat-Velocity-"$version".jar "$out/proxy/"
cp proxy/bungee/build/libs/BetterVoiceChat-Bungee-"$version".jar "$out/proxy/"
cp backend/bukkit/build/libs/BetterVoiceChat-Bukkit-"$version".jar "$out/server/"
cp api/build/libs/api-"$version".jar "$out/BetterVoiceChat-API-$version.jar"
for jar in client/modern/versions/*/build/libs/BetterVoiceChat-"$version"+*.jar client/legacy/*/build/libs/BetterVoiceChat-"$version"+*.jar; do
  case "$jar" in *-sources.jar|*-dev*.jar) continue ;; esac
  cp "$jar" "$out/mods/"
done

(cd "$out" && find . -name '*.jar' | sort | sed 's#^\./##' | xargs sha256sum > SHA256SUMS)
echo "$(grep -c . "$out/SHA256SUMS") jars in $out"
