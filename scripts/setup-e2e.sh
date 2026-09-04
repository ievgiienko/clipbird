#!/usr/bin/env bash
#
# Prepares a machine to run the Playwright end-to-end tests.
#
#   ./scripts/setup-e2e.sh          download browsers, supply missing libraries, verify
#   ./scripts/setup-e2e.sh --check  verify only, download and change nothing
#
# Safe to re-run, and it has to be re-run after a Playwright version bump: a bump
# replaces the browser bundles with new revisions built against different libraries.
#
# Why anything beyond the download is needed: the WebKit bundle is built for
# Ubuntu and links sonames that a rolling distribution no longer ships, currently
# ICU 74 and six flite voices. Rather than asking every machine to install those
# system-wide, the missing files are dropped into the bundle's own library
# directory, which its launcher already puts on LD_LIBRARY_PATH. Nothing outside
# the browser cache is touched and no root is required.
#
# Chromium and Firefox have needed nothing so far. They are still verified,
# because a silent failure to start is the confusing way to discover otherwise.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS_CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/secure-clipboard-e2e"
CHECK_ONLY=0
[[ ${1:-} == "--check" ]] && CHECK_ONLY=1

info() { printf '\033[1m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[33mwarning:\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }

browsers_dir() { echo "${PLAYWRIGHT_BROWSERS_PATH:-$HOME/.cache/ms-playwright}"; }

# Upstream archives that carry the libraries the bundles miss. Update these when a
# Playwright bump moves the bundle onto a different ICU major.
ICU_URL="https://github.com/unicode-org/icu/releases/download/release-74-2/icu4c-74_2-Ubuntu22.04-x64.tgz"
FLITE_URL="http://archive.ubuntu.com/ubuntu/pool/universe/f/flite/libflite1_2.2-7build1_amd64.deb"

# Which archive provides a given soname.
archive_for() {
    case "$1" in
        libicudata.so.*|libicuuc.so.*|libicui18n.so.*) echo "icu" ;;
        libflite*)                                     echo "flite" ;;
        *)                                             echo "" ;;
    esac
}

# Unpacks an archive once into the dependency cache and echoes its root. Layouts
# differ between archives, so callers locate files by name rather than by path.
unpack() {
    local key=$1 dir="$DEPS_CACHE/$1"
    if [[ ! -d $dir ]]; then
        mkdir -p "$dir" || return 1
        case $key in
            icu)
                curl -sSL -o "$dir/archive" "$ICU_URL" || return 1
                tar xzf "$dir/archive" -C "$dir" || return 1
                ;;
            flite)
                curl -sSL -o "$dir/archive" "$FLITE_URL" || return 1
                ( cd "$dir" && bsdtar -xf archive && bsdtar -xf data.tar.* ) || return 1
                ;;
        esac
    fi
    echo "$dir"
}

# Prints the sonames an executable cannot resolve.
missing_libs() {
    LD_LIBRARY_PATH="${2:-}" ldd "$1" 2>/dev/null | awk '/not found/{print $1}' | sort -u
}

# Copies one soname, and every file sharing its base name, into a bundle.
supply() {
    local soname=$1 target=$2 key src base files
    key=$(archive_for "$soname")
    [[ -n $key ]] || { warn "no known source for $soname"; return 1; }
    src=$(unpack "$key") || { warn "could not fetch the $key archive"; return 1; }
    base=${soname%%.so*}
    mapfile -t files < <(find "$src" \( -type f -o -type l \) -name "$base.so.*" 2>/dev/null)
    ((${#files[@]})) || { warn "$soname not found in the $key archive"; return 1; }
    cp -P "${files[@]}" "$target/" 2>/dev/null || return 1
    return 0
}

install_browsers() {
    info "Downloading browser bundles"
    # The Arch gradle package exports GRADLE_HOME, which makes the wrapper run the
    # system distribution instead of the one it downloads. That one has no lib/api,
    # so compiling the Kotlin build script fails before anything else happens.
    ( cd "$REPO_ROOT" && env -u GRADLE_HOME ./gradlew --quiet installPlaywrightBrowsers ) \
        || fail "browser download failed"
}

fix_webkit() {
    local cache root wpe lib libs soname
    cache=$(browsers_dir)
    shopt -s nullglob
    for root in "$cache"/webkit*/; do
        for wpe in "$root"minibrowser-wpe "$root"minibrowser-gtk; do
            lib="$wpe/sys/lib"
            [[ -x $wpe/bin/MiniBrowser && -d $lib ]] || continue
            mapfile -t libs < <(missing_libs "$wpe/bin/MiniBrowser" "$wpe/lib:$lib")
            ((${#libs[@]})) || continue
            info "supplying ${#libs[@]} libraries to $(basename "$root")/$(basename "$wpe")"
            for soname in "${libs[@]}"; do
                supply "$soname" "$lib"
            done
        done
    done
    shopt -u nullglob
}

verify() {
    local cache status=0 exe root wpe libs out
    cache=$(browsers_dir)
    shopt -s nullglob

    for exe in "$cache"/chromium-*/chrome-linux*/chrome "$cache"/firefox-*/firefox/firefox; do
        mapfile -t libs < <(missing_libs "$exe")
        if ((${#libs[@]})); then
            warn "$(basename "$(dirname "$(dirname "$exe")")") cannot start, missing: ${libs[*]}"
            status=1
        else
            info "$(basename "$(dirname "$(dirname "$exe")")"): $("$exe" --version 2>&1 | head -1)"
        fi
    done

    for root in "$cache"/webkit*/; do
        wpe="$root/minibrowser-wpe"
        [[ -x $wpe/bin/MiniBrowser ]] || continue
        mapfile -t libs < <(missing_libs "$wpe/bin/MiniBrowser" "$wpe/lib:$wpe/sys/lib")
        if ((${#libs[@]})); then
            warn "$(basename "$root") cannot start, missing: ${libs[*]}"
            status=1
            continue
        fi
        out=$("$root/pw_run.sh" --headless --version 2>&1 | grep -v GStreamer | tail -1)
        if [[ $out == *WebKit* ]]; then
            info "$(basename "$root"): $out"
        else
            warn "$(basename "$root") does not start: $out"
            status=1
        fi
    done

    shopt -u nullglob
    return $status
}

if [[ $CHECK_ONLY -eq 0 ]]; then
    install_browsers
    fix_webkit
fi

info "Verifying browsers"
if verify; then
    info "Ready. Run the tests with: env -u GRADLE_HOME ./gradlew e2eTest"
else
    fail "Some browsers still do not start. See the warnings above."
fi
