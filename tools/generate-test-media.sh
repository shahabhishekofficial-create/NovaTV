#!/usr/bin/env bash
# CI-only synthetic streams. Nothing generated here is packaged into NovaTV.
set -euo pipefail

output="${1:-/tmp/novatv-media}"
rm -rf "$output"
mkdir -p "$output"

ffmpeg -hide_banner -loglevel error \
  -f lavfi -i 'testsrc2=size=1280x720:rate=24' \
  -f lavfi -i 'sine=frequency=1000:sample_rate=48000' \
  -t 60 -shortest \
  -c:v libx264 -preset ultrafast -crf 23 -pix_fmt yuv420p \
  -profile:v main -level:v 3.1 -g 48 -keyint_min 48 -sc_threshold 0 \
  -c:a aac -b:a 96k -ar 48000 -ac 2 \
  -movflags +faststart "$output/video.mp4"

ffmpeg -hide_banner -loglevel error -i "$output/video.mp4" \
  -c copy -f hls -hls_time 2 -hls_list_size 0 \
  "$output/video.m3u8"

# The same valid segments without ENDLIST exercise live-HLS pause/resume behavior.
sed '/#EXT-X-ENDLIST/d' "$output/video.m3u8" > "$output/live.m3u8"

ffmpeg -hide_banner -loglevel error -i "$output/video.mp4" \
  -c copy -bsf:v h264_mp4toannexb -f mpegts "$output/video.ts"

ffmpeg -hide_banner -loglevel error -i "$output/video.mp4" \
  -c copy -f flv "$output/video.flv"

printf 'Generated CI-only media in %s\n' "$output"
ls -lh "$output"
