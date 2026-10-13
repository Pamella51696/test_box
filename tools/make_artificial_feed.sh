#!/usr/bin/env bash
# Four solid-color test clips for VideoStreamingServer (default -Dpanels: 1=front, 4=right, 2=rear, 3=left).
# Usage: tools/make_artificial_feed.sh [output_dir]
set -euo pipefail
OUT="${1:-$(dirname "$0")/../artificial-feed}"
FONT_BOLD=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf
FONT=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf
mkdir -p "$OUT"
gen() {
  local n=$1 color=$2 label=$3
  ffmpeg -y -loglevel error -f lavfi -i "color=c=${color}:s=640x360:r=30:d=8" \
    -vf "drawtext=fontfile=${FONT_BOLD}:text='CAM ${label}':fontsize=42:fontcolor=white:x=(w-tw)/2:y=(h-th)/2-20,drawtext=fontfile=${FONT}:text='artificial feed':fontsize=22:fontcolor=white@0.8:x=(w-tw)/2:y=(h-th)/2+30" \
    -pix_fmt yuv420p "${OUT}/${n}.mp4"
}
gen 1 blue FRONT
gen 2 red REAR
gen 3 darkorange LEFT
gen 4 green RIGHT
echo "Wrote ${OUT}/1.mp4 .. 4.mp4"
echo "Start: java ... -Dvideo.dir=${OUT} -Dwp5.simulate=true -Dwp5.sim.curbM=0.38 ... VideoStreamingServer 9092"
