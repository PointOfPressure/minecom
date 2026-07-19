#!/usr/bin/env bash
# Reproducibly build + install the vibenilla worldgen library as a BINARY
# dependency of minecom (docs/TIER4-NETHER-DESIGN.md "ADOPT"; docs/AUDIT.md,
# NOTICE). We adopt their nether generator through the region-diff harness only
# (GenRegions "nether_vibenilla" token); their source is NEVER vendored into
# src/ — it stays a pinned Maven artifact.
#
# Pin: github.com/vibenilla/worldgen @ ffaafa1 (Apache-2.0), the commit
# independently measured on 2026-07-19 at nether 99.7650% blocks / 100% biomes
# against minecom's cached vanilla ground truth (seed 20260708). Installs to the
# local Maven repo as rocks.minestom:worldgen:26.2-ffaafa1 so pom.xml resolves it.
#
# Idempotent: safe to re-run. Requires JDK 25 (their gradle toolchain) + git +
# an internet connection (gradle wrapper + minestom/slf4j compileOnly deps).
#
# Build note: their `jar` task depends only on main sources (compileOnly
# minestom + slf4j-api), NOT on `setupVanillaServer`, so the missing
# scripts/StripTypeAnnotations.java (gitignored, never in an outsider clone)
# does NOT block the library build. We still pre-seed the vanilla server jars
# from ~/mc-26.2 and stub server-stripped.jar so a stray `setupVanillaServer`
# invocation (or their parity tests) can't reach out to Mojang.
set -euo pipefail

PIN="ffaafa1"
SRC="${VIBENILLA_SRC:-$HOME/vibenilla-worldgen}"
MC="${MC_26_2:-$HOME/mc-26.2}"
GROUP="rocks.minestom"
ARTIFACT="worldgen"
VERSION="26.2-${PIN}"

echo "[fetch_vibenilla] pin ${PIN} -> ${GROUP}:${ARTIFACT}:${VERSION}"

# 1. clone (or update) at the pinned SHA
if [ ! -d "${SRC}/.git" ]; then
  echo "[fetch_vibenilla] cloning into ${SRC}"
  git clone https://github.com/vibenilla/worldgen.git "${SRC}"
fi
cd "${SRC}"
git fetch --quiet origin || true
git checkout --quiet "${PIN}"
echo "[fetch_vibenilla] HEAD: $(git rev-parse --short HEAD) $(git log -1 --format=%s)"

# 2. pre-seed the vanilla server jars (Mojang data is NEVER committed; reuse the
#    already-provisioned ~/mc-26.2 so no download hits piston-meta). The strip
#    step needs scripts/StripTypeAnnotations.java which is gitignored upstream,
#    so we copy server.jar -> server-stripped.jar as a stand-in.
mkdir -p data/mc/26.2
if [ ! -f data/mc/26.2/server-bundler.jar ] && [ -f "${MC}/server-bundler.jar" ]; then
  cp "${MC}/server-bundler.jar" data/mc/26.2/server-bundler.jar
fi
if [ ! -f data/mc/26.2/server.jar ] && [ -f "${MC}/versions/26.2/server-26.2.jar" ]; then
  cp "${MC}/versions/26.2/server-26.2.jar" data/mc/26.2/server.jar
fi
if [ -f data/mc/26.2/server.jar ] && [ ! -f data/mc/26.2/server-stripped.jar ]; then
  cp data/mc/26.2/server.jar data/mc/26.2/server-stripped.jar
fi

# 3. build only the library artifact
echo "[fetch_vibenilla] building library jar (gradle)"
nice -n 15 ./gradlew --no-daemon jar

JAR="$(ls -1 build/libs/worldgen-*.jar | head -1)"
echo "[fetch_vibenilla] built ${JAR}"

# 4. install to the local Maven repo under the pinned coordinates
nice -n 15 mvn -q install:install-file \
  -Dfile="${JAR}" \
  -DgroupId="${GROUP}" \
  -DartifactId="${ARTIFACT}" \
  -Dversion="${VERSION}" \
  -Dpackaging=jar \
  -DgeneratePom=true

echo "[fetch_vibenilla] installed ${GROUP}:${ARTIFACT}:${VERSION} to ~/.m2"
