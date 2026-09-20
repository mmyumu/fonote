#!/usr/bin/env bash
# Cuts a version and publishes it: one command from a clean tree to a phone offering the update.
#
# Fonote ships two artefacts out of one repository: the backend image, and the APK the server
# hands out. They move at their own pace — 1.2.1 was an app-only release, the image stayed at
# 1.2.0 — so nothing here is left to judgement.
#
# The script does two separate things, and either can be a no-op:
#
#   - Given a version, it bumps what moved since the previous tag, commits and tags. What makes
#     the image move is the set of files the `.dockerignore` allowlist lets in, a set that
#     includes an Android asset — precisely the kind of detail one forgets.
#   - Then, always, it makes the server match the tree: it asks the VPS which image it runs and
#     the server which APK it offers, and publishes whichever lags behind. That second half is
#     idempotent, so a release interrupted halfway is finished by running the script again, and
#     a version tagged but never published is caught without a new tag.
#
# Usage: scripts/release.sh [X.Y.Z] [--dry-run]
#        scripts/release.sh            publishes what the current tree says, if the server lags
#        scripts/release.sh 1.4.0      cuts 1.4.0 first
#
# Overridable: FONOTE_VPS (ssh host), FONOTE_VPS_DIR (compose folder on it), FONOTE_VPS_APK_DIR
# (apk folder), FONOTE_URL (server), FONOTE_IMAGE (registry repository).
set -euo pipefail
source "$(dirname -- "${BASH_SOURCE[0]}")/android-env.sh"

version='' dry=''
for argument in "$@"; do
    case "$argument" in
        --dry-run) dry=1 ;;
        *) [[ -z "$version" ]] || { echo "One version at a time." >&2; exit 2; }; version=$argument ;;
    esac
done
if [[ -n "$version" && ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Usage: scripts/release.sh [X.Y.Z] [--dry-run]" >&2
    exit 2
fi

vps=${FONOTE_VPS:-mmyumu.fr}
remote_apk=${FONOTE_VPS_APK_DIR:-docker/fonote/apk}
remote_dir=${FONOTE_VPS_DIR:-$(dirname -- "$remote_apk")}
url=${FONOTE_URL:-https://fonote.mmyumu.fr}
repository=${FONOTE_IMAGE:-registry.mmyumu.fr/fonote-backend}
cd "$FONOTE_ROOT"

# What is published should be what is committed: a version built from changes nobody committed
# cannot be rebuilt or read back later.
if [[ -n "$(git status --porcelain --untracked-files=no)" ]]; then
    # A dry run publishes nothing, so it is allowed to answer from a tree still being worked on.
    [[ -n "$dry" ]] || { echo "Uncommitted changes: commit them before releasing." >&2; exit 1; }
    echo "Uncommitted changes: a real run would refuse them."
fi

tagged=''
if [[ -n "$version" ]]; then
    if git rev-parse -q --verify "refs/tags/$version" >/dev/null; then
        echo "Tag $version already exists." >&2
        exit 1
    fi
    # The previous tag is the boundary. Before the first one, everything counts as new.
    previous=$(git describe --tags --abbrev=0 2>/dev/null || git rev-list --max-parents=0 HEAD | tail -1)
    # The image holds the Dockerfile's doing and the files the allowlist lets in — tests
    # excepted, since they never enter it. The APK holds the Android sources, minus what only
    # debug and the instrumented checks read.
    backend=$(git diff --name-only "$previous..HEAD" -- backend/Dockerfile .dockerignore 'backend/*.py' \
        backend/competitions.json android/app/src/main/assets/match.json | grep -v '^backend/test_' || true)
    android=$(git diff --name-only "$previous..HEAD" -- android \
        | grep -vE '^android/(checks|app/src/(debug|androidTest))/' || true)
    if [[ -z "$backend$android" ]]; then
        echo "Nothing has changed since $previous: there is no $version to cut." >&2
        echo "Run the script without a version to publish what $previous already says." >&2
        exit 1
    fi

    code=$(sed -n 's/.*versionCode  *\([0-9]\+\).*/\1/p' android/app/build.gradle)
    echo "Since $previous:"
    [[ -n "$backend" ]] && echo "  server → $repository:$version" || echo "  server → untouched"
    [[ -n "$android" ]] && echo "  app    → $version, version code $((code + 1))" || echo "  app    → untouched"

    if [[ -z "$dry" ]]; then
        # One commit carries both bumps, and says which artefact this version actually moves.
        body=''
        if [[ -n "$android" ]]; then
            sed -i -E "s/(versionCode +)[0-9]+/\1$((code + 1))/; s/(versionName +')[^']+'/\1$version'/" \
                android/app/build.gradle
            grep -q "versionName '$version'" android/app/build.gradle \
                || { echo "The app version did not take." >&2; exit 1; }
            body+="- The application reads $version, and its version code goes to $((code + 1)) so"$'\n'
            body+="  that the release installs over the previous one as an update."$'\n'
        fi
        if [[ -n "$backend" ]]; then
            sed -i -E "s|(fonote-backend:)[0-9]+\.[0-9]+\.[0-9]+|\1$version|g" compose.yml .env.example
            grep -q "fonote-backend:$version" compose.yml \
                || { echo "The image tag did not take." >&2; exit 1; }
            body+="- The backend image goes to $version."$'\n'
        else
            body+="- The server is unchanged: the backend image stays on its version."$'\n'
        fi
        git add android/app/build.gradle compose.yml .env.example
        git diff --cached --quiet || git commit -q -m "Fonote $version" -m "${body%$'\n'}"
        git tag -a "$version" -m "Fonote $version"
        tagged=1
        echo "Tagged $version."
    fi
fi

# What the tree says should be out there, and what is actually out there.
wanted_image=$repository:$(sed -n 's|.*fonote-backend:\([0-9.]*\).*|\1|p' compose.yml | head -1)
wanted_code=$(sed -n 's/.*versionCode  *\([0-9]\+\).*/\1/p' android/app/build.gradle)
running_image=$(ssh "$vps" "docker inspect -f '{{.Config.Image}}' fonote-backend" 2>/dev/null || echo 'none')
served_code=$(python3 - "$url/v1/health" <<'EOF' || echo 'none'
import json, sys, urllib.request
health = json.load(urllib.request.urlopen(sys.argv[1], timeout=15))
print(((health.get('app') or {}).get('latest') or {}).get('code', 'none'))
EOF
)

echo "The VPS runs $running_image, the tree says $wanted_image."
echo "The server offers version code $served_code, the tree says $wanted_code."
[[ -z "$dry" ]] || exit 0

if [[ "$running_image" != "$wanted_image" ]]; then
    # The token never enters the image: it is read from the environment at run time, so any
    # value satisfies compose's interpolation while building and pushing.
    echo "Building and pushing $wanted_image"
    FONOTE_TOKEN=unused FONOTE_BACKEND_IMAGE=$wanted_image docker compose build
    FONOTE_TOKEN=unused FONOTE_BACKEND_IMAGE=$wanted_image docker compose push
    # The VPS's compose.yml is its own file, not a copy of this one: hardened, read-only, on
    # two networks, reading the token from `.env`, and naming the image outright with no
    # interpolation. So the tag is moved there and nowhere else — `grep image compose.yml` on
    # the VPS keeps telling the truth, and `.env`, which holds FONOTE_TOKEN, is never touched.
    # The substitution is anchored on the image actually running, so it fires once or not at all.
    ssh "$vps" "cd '$remote_dir' && sed -i 's|$running_image|$wanted_image|' compose.yml \
        && grep -q 'image: $wanted_image' compose.yml \
        && docker compose pull && docker compose up -d"
    running_image=$(ssh "$vps" "docker inspect -f '{{.Config.Image}}' fonote-backend")
    [[ "$running_image" == "$wanted_image" ]] || { echo "The VPS runs $running_image." >&2; exit 1; }
    echo "The VPS now runs $running_image."
fi

if [[ "$served_code" != "$wanted_code" ]]; then
    # Builds the signed release, copies it beside the server and reads it back from the outside.
    bash "$FONOTE_ROOT/scripts/publish-apk.sh"
fi

[[ -z "$tagged" ]] || git push --follow-tags
echo "$url is up to date."
