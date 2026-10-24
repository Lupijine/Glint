#!/usr/bin/env sh
# Refreshes the gallery-dl that ships with Glint (app/src/main/assets/gallery-dl) from PyPI:
# gallery-dl itself and the pure-Python packages it needs. The app also updates gallery-dl on its
# own from PyPI; this only changes the version a fresh install starts with.
set -eu
assets="$(dirname "$0")/../app/src/main/assets/gallery-dl"
work="$(mktemp -d)"
pip download --quiet --no-deps --only-binary=:all: --python-version 3.11 --implementation py \
    --platform any --dest "$work" gallery-dl requests urllib3 idna charset-normalizer certifi
cp "$work"/gallery_dl-*-py3-none-any.whl "$assets/gallery_dl.whl"
for package in requests urllib3 idna charset_normalizer certifi; do
    cp "$work"/"$package"-*-py3-none-any.whl "$assets/deps/$package.whl"
done
basename "$work"/gallery_dl-*-py3-none-any.whl | sed -e 's/^gallery_dl-//' -e 's/-py3-none-any.whl$//' > "$assets/version.txt"
rm -r "$work"
echo "gallery-dl $(cat "$assets/version.txt")"
