#!/usr/bin/env bash
#
# Prepares a release: version bump + commit + tag, reproducible build from a clean clone
# of the tag, signed APKs (GitHub / F-Droid) and signed AAB (Google Play).
#
#   scripts/release.sh 0.3.0
#
# Nothing is pushed: check, then `git push origin main v0.3.0` yourself.
#
# If the tag already exists, the bump/commit/tag step is skipped and the tag is rebuilt
# as is. To fix a release before pushing: commit the fix, `git tag -f v0.3.0`, run again.
#
# Changelogs (shown by F-Droid, to paste into the Play Console): the script asks for
# fastlane/metadata/android/{en-US,fr-FR}/changelogs/<versionCode×10+1>.txt and copies
# it to the other ABIs' versionCodes (+2, +3, +4).
#
# Environment (defaults in brackets):
#   KEYSTORE [galene-release.jks]  KEY_ALIAS [galene]  BUILD_TOOLS [35.0.0]
#   ANDROID_HOME [/opt/android]    JAVA_HOME [/usr/lib/jvm/java-21-openjdk-amd64]
#   KS_PASS: keystore password (asked if unset)

set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "── $* ──"; }

version=${1:-}
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "usage: $0 <x.y.z>"
tag="v$version"

repo=$(git rev-parse --show-toplevel)
cd "$repo"

export ANDROID_HOME=${ANDROID_HOME:-/opt/android}
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}
export PATH="$JAVA_HOME/bin:$PATH"
build_tools="$ANDROID_HOME/build-tools/${BUILD_TOOLS:-35.0.0}"
keystore=$(realpath "${KEYSTORE:-galene-release.jks}")
alias=${KEY_ALIAS:-galene}
gradle_file=app/build.gradle.kts
abis=(armeabi-v7a arm64-v8a x86 x86_64)    # ABI code = index + 1 (see app/build.gradle.kts)

[[ -x $build_tools/apksigner ]] || die "apksigner not found in $build_tools"
[[ -f $keystore ]] || die "keystore not found: $keystore"
command -v jarsigner >/dev/null || die "jarsigner not found (JDK)"

# ─── 1. Version bump, commit, tag ─────────────────────────────────────────
if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
    step "Tag $tag exists: rebuilding it as is"
else
    step "Preparing $tag"
    [[ $(git branch --show-current) == main ]] || die "not on main"

    current_name=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$gradle_file")
    current_code=$(sed -n 's/^val appVersionCode = \([0-9]*\)/\1/p' "$gradle_file")
    [[ -n $current_name && -n $current_code ]] || die "versionName / appVersionCode not found in $gradle_file"
    [[ $(printf '%s\n%s\n' "$current_name" "$version" | sort -V | tail -1) == "$version" && $current_name != "$version" ]] \
        || die "$version is not newer than $current_name"
    code=$((current_code + 1))

    # Only changelogs may be pending: everything else must already be committed
    dirty=$(git status --porcelain --untracked-files=no | grep -v ' fastlane/metadata/android/.*/changelogs/' || true)
    [[ -z $dirty ]] || die "uncommitted changes:"$'\n'"$dirty"

    missing=0
    for locale in en-US fr-FR; do
        dir=fastlane/metadata/android/$locale/changelogs
        if [[ ! -s $dir/${code}1.txt ]]; then
            echo "missing changelog: $dir/${code}1.txt" >&2
            missing=1
        elif (( $(wc -m < "$dir/${code}1.txt") > 500 )); then
            die "$dir/${code}1.txt is over 500 characters (F-Droid / Play limit)"
        fi
    done
    (( missing == 0 )) || die "write the changelog(s) above (versionCode $code), then run again"
    for locale in en-US fr-FR; do
        dir=fastlane/metadata/android/$locale/changelogs
        for i in 2 3 4; do cp "$dir/${code}1.txt" "$dir/${code}$i.txt"; done
    done

    sed -i "s/^val appVersionCode = .*/val appVersionCode = $code/" "$gradle_file"
    sed -i "s/^\( *versionName = \)\".*\"/\1\"$version\"/" "$gradle_file"

    git add "$gradle_file" fastlane/metadata/android/*/changelogs/"${code}"[1-4].txt
    git commit -q -m "$version"
    git tag "$tag"
    echo "commit $(git rev-parse --short HEAD) \"$version\", tag $tag (versionCode $code)"
fi

# ─── 2. Reproducible build from a clean clone of the tag ──────────────────
# Same flags as F-Droid's check: no build cache, no incremental Kotlin compilation
step "Building $tag"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
git -c advice.detachedHead=false clone -q --no-local --branch "$tag" "$repo" "$work/src"
code=$(sed -n 's/^val appVersionCode = \([0-9]*\)/\1/p' "$work/src/$gradle_file")
(
    cd "$work/src"
    ./gradlew -q clean assembleRelease bundleRelease \
        --no-build-cache --rerun-tasks -Pkotlin.incremental=false --no-watch-fs
)
built="$work/src/app/build/outputs"

# ─── 3. Signing ───────────────────────────────────────────────────────────
step "Signing"
if [[ -z ${KS_PASS:-} ]]; then
    read -rsp "Keystore password ($keystore): " KS_PASS; echo
fi
export KS_PASS

out="$repo/release/$tag"
rm -rf "$out"
mkdir -p "$out"

for abi in "${abis[@]}"; do
    # --alignment-preserved: F-Droid copies this signature onto its own build
    "$build_tools/apksigner" sign --alignment-preserved \
        --ks "$keystore" --ks-key-alias "$alias" --ks-pass env:KS_PASS \
        --out "$out/galene-$version-$abi.apk" \
        "$built/apk/release/app-$abi-release-unsigned.apk"
    rm -f "$out/galene-$version-$abi.apk.idsig"
done

jarsigner -sigalg SHA256withRSA -digestalg SHA-256 \
    -keystore "$keystore" -storepass:env KS_PASS \
    -signedjar "$out/galene-$version.aab" \
    "$built/bundle/release/app-release.aab" "$alias" >/dev/null

# ─── 4. Checks ────────────────────────────────────────────────────────────
step "Checking"
for abi in "${abis[@]}"; do
    apk="$out/galene-$version-$abi.apk"
    "$build_tools/apksigner" verify "$apk"
    badging=$("$build_tools/aapt2" dump badging "$apk" | sed -n 1p)
    grep -q "versionName='$version'" <<<"$badging" || die "$apk: wrong versionName ($badging)"
    echo "ok  $(basename "$apk")  $(grep -o "versionCode='[0-9]*'" <<<"$badging")"
done
jarsigner -verify "$out/galene-$version.aab" | grep "^jar verified" >/dev/null || die "$out/galene-$version.aab: signature check failed"
echo "ok  galene-$version.aab"

(cd "$out" && sha256sum -- *.apk *.aab > SHA256SUMS)

cat <<EOF

Done: $out
$(ls -1 "$out" | sed 's/^/  /')

Next:
  git push origin main $tag
  gh release create $tag release/$tag/*.apk --title $version \\
      --notes-file fastlane/metadata/android/en-US/changelogs/${code}1.txt
  Play Console → new release → upload release/$tag/galene-$version.aab
                 (release notes: fastlane/metadata/android/*/changelogs/${code}1.txt)
EOF
