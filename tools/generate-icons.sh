#!/usr/bin/env bash
# Generate mipmap PNGs from tools/icon-source.png using ImageMagick.
# Place your 1024x1024 source PNG at tools/icon-source.png then run this script.
# Requirements: imagemagick (brew install imagemagick / apt install imagemagick)

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$SCRIPT_DIR/icon-source.png"
RES="$SCRIPT_DIR/../app/src/main/res"

if [ ! -f "$SOURCE" ]; then
  echo "ERROR: $SOURCE not found. Drop your 1024x1024 logo PNG there first."
  exit 1
fi

declare -A SIZES=(
  [mipmap-mdpi]=48
  [mipmap-hdpi]=72
  [mipmap-xhdpi]=96
  [mipmap-xxhdpi]=144
  [mipmap-xxxhdpi]=192
)

for dir in "${!SIZES[@]}"; do
  size="${SIZES[$dir]}"
  out="$RES/$dir/ic_launcher.png"
  convert "$SOURCE" -resize "${size}x${size}" "$out"
  cp "$out" "$RES/$dir/ic_launcher_round.png"
  echo "Generated ${size}x${size} -> $out"
done

echo "Done. Commit the updated mipmap PNGs."
