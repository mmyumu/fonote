#!/usr/bin/env bash
# Builds the signed release and hands it to the server, which offers it to every installed app.
#
# The APK is not part of the server image: compose mounts the VPS folder `apk/` on `/apk`, and
# the server reads it on every request. Publishing is therefore a copy, with no push and no
# restart. The APK goes first and its metadata last, each under a temporary name then renamed:
# the version is read off the metadata, so no app ever hears of an APK still being copied.
#
# Overridable: FONOTE_VPS (ssh host), FONOTE_VPS_APK_DIR (folder on it), FONOTE_URL (server).
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"
vps=${FONOTE_VPS:-mmyumu.fr}
remote=${FONOTE_VPS_APK_DIR:-docker/fonote/apk}
url=${FONOTE_URL:-https://fonote.mmyumu.fr}
out="$FONOTE_ROOT/android/app/build/outputs/apk/release"

# What is published should be what is committed: an APK built from changes nobody committed
# cannot be rebuilt or read back later.
if [[ -n "$(git -C "$FONOTE_ROOT" status --porcelain --untracked-files=no)" ]]; then
    echo "Uncommitted changes: commit them before publishing." >&2
    exit 1
fi

(cd "$FONOTE_ROOT/android" && ./gradlew -q :app:assembleRelease)

read -r apk code name < <(python3 - "$out/output-metadata.json" <<'EOF'
import json, sys
metadata = json.load(open(sys.argv[1]))
assert metadata['applicationId'] == 'fr.fonote', metadata['applicationId']
element = metadata['elements'][0]
print(element['outputFile'], element['versionCode'], element['versionName'])
EOF
)

# Android only installs an update signed with the installed app's key: an unsigned or debug
# APK would be announced to every phone and then refused by all of them.
if [[ "$apk" == *unsigned* ]]; then
    echo "$apk is unsigned: ~/.android/fonote-signing.properties is missing." >&2
    exit 1
fi
apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | tail -1)
signer=$("$apksigner" verify --print-certs "$out/$apk" | grep -m1 'certificate DN')
if [[ "$signer" == *"Android Debug"* ]]; then
    echo "$apk is signed with the debug key." >&2
    exit 1
fi
sha=$(sha256sum "$out/$apk" | cut -d' ' -f1)

echo "Publishing $name (code $code) to $vps:$remote"
scp -q "$out/$apk" "$vps:$remote/.$apk.part"
scp -q "$out/output-metadata.json" "$vps:$remote/.output-metadata.json.part"
# One session, in order: the APK in place, then the metadata that announces it, then the
# APKs no metadata points to any more.
ssh "$vps" "cd '$remote' && mv '.$apk.part' '$apk' \
    && mv .output-metadata.json.part output-metadata.json \
    && find . -maxdepth 1 -name 'fonote-*.apk' ! -name '$apk' -print -delete"

# Read back from the outside, the way the apps will see it.
python3 - "$url/v1/health" "$code" "$sha" <<'EOF'
import json, sys, urllib.request
health, code, sha = sys.argv[1], int(sys.argv[2]), sys.argv[3]
latest = (json.load(urllib.request.urlopen(health, timeout=15)).get('app') or {}).get('latest')
if not latest or latest['code'] != code or latest['sha256'] != sha:
    sys.exit(f'The server does not offer this APK: {latest}')
print(f"The server offers {latest['name']} (code {latest['code']}, {latest['size']} bytes)")
EOF
