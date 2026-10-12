#!/usr/bin/env bash
#
# Prepares an Arch machine to run the Playwright end-to-end tests. On Debian or
# Ubuntu, Playwright installs the dependencies itself and this script is not
# needed; on other distributions the equivalent libraries go in by hand. Either
# way the script says so and exits.
#
#   ./scripts/setup-e2e.sh          download browsers, supply missing libraries, verify
#   ./scripts/setup-e2e.sh --check  verify only, download and change nothing
#
# Safe to re-run, and it has to be re-run after a Playwright version bump: a bump
# replaces the browser bundles with new revisions built against different libraries.
#
# Why anything beyond the download is needed: the WebKit bundle is built for
# Ubuntu and links sonames that a rolling distribution resolves differently.
# There are two kinds, handled two ways:
#
#   1. Libraries Arch no longer ships at all, currently ICU 74 and six flite
#      voices. These are fetched from upstream and dropped into the bundle's own
#      library directory, which its launcher already puts on LD_LIBRARY_PATH.
#      Nothing outside the browser cache is touched and no root is required.
#
#   2. Libraries Arch does ship, just under a package that a desktop install may
#      not have pulled in (enchant, hyphen, harfbuzz-icu, libmanette, and the
#      legacy libxml2.so.2). Vendoring a foreign build of these into the bundle
#      would be worse than missing them, since they link the rest of the system,
#      so the script names the packages and leaves the install to the user.
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

# Packages that still need to be installed, collected during verify and reported
# once at the end so the user gets a single pacman line rather than a soname hunt.
REMEDY_PKGS=()

# Upstream archives that carry the libraries Arch no longer ships. Update these
# when a Playwright bump moves the bundle onto a different ICU major.
ICU_URL="https://github.com/unicode-org/icu/releases/download/release-74-2/icu4c-74_2-Ubuntu22.04-x64.tgz"
FLITE_URL="http://archive.ubuntu.com/ubuntu/pool/universe/f/flite/libflite1_2.2-7build1_amd64.deb"

# Which archive provides a given soname, for the libraries we vendor into the bundle.
archive_for() {
    case "$1" in
        libicudata.so.*|libicuuc.so.*|libicui18n.so.*) echo "icu" ;;
        libflite*)                                     echo "flite" ;;
        *)                                             echo "" ;;
    esac
}

# Which Arch package provides a given soname, for the libraries we install rather
# than vendor. libxml2.so.2 is matched exactly: the current libxml2 ships
# libxml2.so.16, and only libxml2-legacy carries the .so.2 the bundle wants.
pkg_for() {
    case "$1" in
        libenchant-2.so.*)    echo "enchant" ;;
        libhyphen.so.*)       echo "hyphen" ;;
        libharfbuzz-icu.so.*) echo "harfbuzz-icu" ;;
        libmanette-0.2.so.*)  echo "libmanette" ;;
        libxml2.so.2)         echo "libxml2-legacy" ;;
        *)                    echo "" ;;
    esac
}

# The newest webkit revision in the cache is the one the current Playwright drives.
# Older revisions linger after a version bump and link long-gone libraries, so they
# are dead weight rather than something to make start. Echoes a path without a
# trailing slash, or nothing when no webkit bundle is present.
active_webkit() {
    local cache best="" bestn=-1 root rev
    cache=$(browsers_dir)
    shopt -s nullglob
    for root in "$cache"/webkit*/; do
        root=${root%/}
        rev=${root##*-}
        [[ $rev == *[!0-9]* ]] && continue
        if ((rev > bestn)); then bestn=$rev; best=$root; fi
    done
    shopt -u nullglob
    echo "$best"
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
    [[ -n $key ]] || return 1
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

# Vendors the archive-backed libraries (ICU, flite) into the active bundle. The
# rest are left for verify to report, since they are installed, not vendored.
fix_webkit() {
    local root wpe lib libs soname n
    root=$(active_webkit)
    [[ -n $root ]] || return 0
    shopt -s nullglob
    for wpe in "$root/minibrowser-wpe" "$root/minibrowser-gtk"; do
        lib="$wpe/sys/lib"
        [[ -x $wpe/bin/MiniBrowser && -d $lib ]] || continue
        mapfile -t libs < <(missing_libs "$wpe/bin/MiniBrowser" "$wpe/lib:$lib")
        ((${#libs[@]})) || continue
        n=0
        for soname in "${libs[@]}"; do
            [[ -n $(archive_for "$soname") ]] || continue
            supply "$soname" "$lib" && ((n++))
        done
        ((n)) && info "supplied $n bundled libraries to $(basename "$root")/$(basename "$wpe")"
    done
    shopt -u nullglob
}

verify() {
    local cache status=0 exe root wpe gtk libs out soname pkg other
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

    root=$(active_webkit)
    if [[ -n $root ]]; then
        wpe="$root/minibrowser-wpe"
        if [[ -x $wpe/bin/MiniBrowser ]]; then
            mapfile -t libs < <(missing_libs "$wpe/bin/MiniBrowser" "$wpe/lib:$wpe/sys/lib")
            if ((${#libs[@]})); then
                warn "$(basename "$root") cannot start, missing: ${libs[*]}"
                status=1
                for soname in "${libs[@]}"; do
                    pkg=$(pkg_for "$soname")
                    if [[ -n $pkg ]]; then
                        REMEDY_PKGS+=("$pkg")
                    elif [[ -n $(archive_for "$soname") ]]; then
                        : # vendored by fix_webkit; a normal run supplies it
                    else
                        warn "no known source for $soname"
                    fi
                done
            else
                out=$("$root/pw_run.sh" --headless --version 2>&1 | grep -v GStreamer | tail -1)
                if [[ $out == *WebKit* ]]; then
                    info "$(basename "$root"): $out"
                else
                    warn "$(basename "$root") does not start: $out"
                    status=1
                fi
            fi
        fi
        # The GTK MiniBrowser is not what the tests drive. Fold any packages it
        # additionally needs into the same remedy, named but not fatal, for anyone
        # who runs it by hand.
        gtk="$root/minibrowser-gtk"
        if [[ -x $gtk/bin/MiniBrowser ]]; then
            mapfile -t libs < <(missing_libs "$gtk/bin/MiniBrowser" "$gtk/lib:$gtk/sys/lib")
            for soname in "${libs[@]}"; do
                pkg=$(pkg_for "$soname")
                [[ -n $pkg ]] && REMEDY_PKGS+=("$pkg")
            done
        fi
        # Older revisions are not driven by the current Playwright. Point them out
        # as removable rather than trying to resolve their obsolete libraries.
        for other in "$cache"/webkit*/; do
            other=${other%/}
            [[ $other == "$root" ]] && continue
            warn "stale bundle $(basename "$other") is unused, remove it with: rm -rf '$other'"
        done
    fi

    shopt -u nullglob
    return $status
}

# This script targets Arch and its derivatives. Vendoring ICU and the flite
# voices and naming pacman packages only makes sense on a rolling distribution
# that resolves the bundle's sonames differently. Playwright automates the
# system dependencies through apt, so only Debian and Ubuntu get that path.
if ! command -v pacman >/dev/null 2>&1; then
    if command -v apt-get >/dev/null 2>&1; then
        warn "this setup is for Arch. On Debian or Ubuntu let Playwright install the deps:"
        printf '    env -u GRADLE_HOME ./gradlew installPlaywrightBrowsers -PplaywrightWithDeps\n' >&2
    else
        warn "this setup is for Arch. Playwright only automates dependencies on Debian and Ubuntu,"
        warn "so here install the libraries WebKit needs with your own package manager. Playwright"
        warn "lists them at https://playwright.dev/docs/browsers#install-system-dependencies"
    fi
    exit 1
fi

if [[ $CHECK_ONLY -eq 0 ]]; then
    install_browsers
    fix_webkit
fi

info "Verifying browsers"
verify
status=$?

if ((${#REMEDY_PKGS[@]})); then
    mapfile -t pkgs < <(printf '%s\n' "${REMEDY_PKGS[@]}" | sort -u)
    warn "WebKit needs libraries Arch ships as packages:"
    printf '    sudo pacman -S %s\n' "${pkgs[*]}" >&2
fi

if ((status == 0)); then
    ((${#REMEDY_PKGS[@]})) && warn "those cover only the GTK MiniBrowser, which the tests do not use"
    info "Ready. Run the tests with: env -u GRADLE_HOME ./gradlew e2eTest"
else
    warn "Install the packages above, then re-run this script."
    fail "Some browsers still do not start. See the warnings above."
fi
