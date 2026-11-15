#!/bin/sh
# Bring the low-latency stream from a shairport-sync fork into patch 0011.
#
#   native/patches/import-low-latency.sh <shairport-sync fork>
#
# The fork is the pinned upstream commit (see the Dockerfile) plus the low-latency work, committed
# or not. Patch 0011 is that work on top of the pinned commit with patches 0001-0010 applied, so
# it applies in the Dockerfile's order. The fork's own index and work tree are left alone.
set -eu
fork=$(cd "$1" && pwd)
patches=$(cd "$(dirname "$0")" && pwd)
commit=$(sed -n 's/^get shairport-sync \([0-9a-f]\{40\}\) .*/\1/p' "$patches/../../Dockerfile")
[ -n "$commit" ] || { echo "no pinned shairport-sync commit in the Dockerfile" >&2; exit 1; }
out="$patches/shairport-sync/0011-airplay-2-low-latency-mode.patch"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The fork's changes from the pinned commit, new files included, through a scratch index.
export GIT_INDEX_FILE="$work/index"
git -C "$fork" read-tree "$commit"
git -C "$fork" add -A
git -C "$fork" diff --cached "$commit" >"$work/fork.diff"
unset GIT_INDEX_FILE
[ -s "$work/fork.diff" ] || { echo "the fork has no changes from $commit" >&2; exit 1; }

# The pinned commit with patches 0001-0010, then the fork's changes on top.
mkdir "$work/base"
git -C "$fork" archive "$commit" | tar -x -C "$work/base"
for p in "$patches"/shairport-sync/*.patch; do
  case "$(basename "$p")" in
    000[1-9]-* | 0010-*) patch -s -d "$work/base" -p1 <"$p" ;;
  esac
done
cp -a "$work/base" "$work/new"
patch -s -d "$work/new" -p1 <"$work/fork.diff"
# patch keeps .orig copies of files it applied with an offset or fuzz
find "$work/base" "$work/new" -name '*.orig' -delete

cd "$work"
git diff --no-index --src-prefix=a/ --dst-prefix=b/ base new |
  sed -e 's#a/base/#a/#g' -e 's#b/base/#b/#g' -e 's#a/new/#a/#g' -e 's#b/new/#b/#g' >"$out" || true
[ -s "$out" ] || { echo "no patch written" >&2; exit 1; }
echo "$out: $(grep -c '^diff --git' "$out") files"
