#!/usr/bin/env bash
#
# Starts the virtual display stack that KORTEX's headed Chromium needs, then
# hands off to the JVM.
#
# Why a fixed display instead of the previous `xvfb-run -a`:
#   `xvfb-run -a` picks a RANDOM free display number. x11vnc has to attach to a
#   known one, so the display is pinned to :99 here and exported via DISPLAY.
#
# Why openbox:
#   Without a window manager, Chromium's <select> dropdowns, file pickers and
#   popups are separate X windows that never raise or take keyboard focus.
#   Recording flows hit <select> constantly. Costs ~10 MB.
#
# Why x11vnc + websockify:
#   Recording only produces steps when a human actually clicks in the browser
#   (RecordingSession's injected listeners fire on real DOM events). On a
#   server there is no such human unless we give them a way in. VNC delivers
#   real X input, so Chromium synthesizes genuinely trusted DOM events and the
#   recording path needs no changes at all.
#
set -e

Xvfb :99 -screen 0 "${SCREEN_GEOMETRY:-1280x900x24}" -nolisten tcp &

# Wait for the display to accept connections before anything tries to use it.
# A blind `sleep` is not enough on a 0.25-vCPU box. Written with an explicit
# `if` rather than `xdpyinfo && break` so it is unambiguously exempt from the
# `set -e` above.
display_ready=""
for _ in $(seq 1 100); do
    if xdpyinfo -display :99 >/dev/null 2>&1; then
        display_ready="yes"
        break
    fi
    sleep 0.2
done
if [ -z "$display_ready" ]; then
    echo "[entrypoint] FATAL: Xvfb :99 did not become ready within 20s"
    exit 1
fi
echo "[entrypoint] Display :99 ready (${SCREEN_GEOMETRY:-1280x900x24})"

openbox &

# -localhost is what makes -nopw safe: x11vnc is then unreachable except
# through nginx -> Caddy, which is basic-auth'd. Never publish 5900 or 6080
# to the host.
x11vnc -display :99 -forever -shared -localhost -nopw -rfbport 5900 -noxdamage -quiet &

java -jar /app/app.jar &
java_pid=$!

# Platforms that auto-detect a single "primary" port from whichever the
# container opens first (Render Web Services) would otherwise lock onto
# 6080, since websockify binds almost instantly while the JVM takes seconds
# — leaving the actual API on 8080 unreachable. Starting websockify only
# after 8080 is already open means the JVM always wins that race. This has
# no effect on docker-compose, which never auto-detects ports.
#
# bash's /dev/tcp probe is unreliable in this image (it reported success
# immediately, before Tomcat had bound anything) — python3 is already a
# websockify dependency here, so use a real socket connect instead.
for _ in $(seq 1 150); do
    if python3 -c '
import socket, sys
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(1)
try:
    s.connect(("127.0.0.1", 8080))
except OSError:
    sys.exit(1)
s.close()
'; then
        break
    fi
    sleep 0.5
done

# Render's own port-scanner runs on its own cadence, not the instant 8080
# opens — a scan that happens to land in the gap between "8080 just opened"
# and "websockify not started yet" still works, but one that lands right as
# websockify starts up can catch both ports in the same cycle and pick
# either. Giving 8080 a full scan interval alone on the field, rather than
# starting websockify the instant the probe succeeds, is what actually wins
# the race.
sleep 20

websockify --web=/usr/share/novnc 0.0.0.0:6080 localhost:5900 &

# ponytail: no process supervisor. If Xvfb, x11vnc or websockify dies
# mid-session the JVM keeps running blind until the container is restarted.
# Add supervisord only if that actually happens in practice.
wait "$java_pid"
