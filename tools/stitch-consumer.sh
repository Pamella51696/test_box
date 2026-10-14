#!/usr/bin/env bash
# Keeps /stitch open so VideoStreamingServer runs the decode loop and VisionFrameTap posts frames.
PORT="${1:-9091}"
exec curl -sN "http://127.0.0.1:${PORT}/stitch" -o /dev/null
