# --- Build stage ---
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q dependency:go-offline

COPY src ./src
RUN mvn -q clean package -DskipTests


# --- Runtime stage ---
# The Playwright base image ships Chromium and every system library it needs,
# already installed at /ms-playwright. Its tag MUST stay in lockstep with the
# com.microsoft.playwright:playwright version in pom.xml (1.45.0) — a mismatch
# makes Playwright try to download browsers at startup, which fails here.
FROM mcr.microsoft.com/playwright/java:v1.45.0-jammy

# Prevent interactive timezone selection during Docker build
ENV DEBIAN_FRONTEND=noninteractive
ENV TZ=Asia/Kolkata

# xvfb      — virtual display for the headed record/play Chromium
# x11vnc    — exposes that display so a human can actually drive the browser
# x11-utils — xdpyinfo, used by entrypoint.sh to wait for the display
# openbox   — window manager; without one, <select> dropdowns and file pickers
#             never raise or take focus
# novnc +
# websockify— browser-based VNC client served over a websocket
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb x11vnc x11-utils openbox novnc websockify \
    && rm -rf /var/lib/apt/lists/*


# SCREEN_GEOMETRY is 1280x900, not 1280x800: Playwright's default viewport is
# 1280x720 and Chromium's own UI chrome adds ~85px, so an 800px-tall screen
# clips the window and you end up scrolling inside noVNC to reach the page.
ENV DISPLAY=:99 \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    SCREEN_GEOMETRY=1280x900x24

WORKDIR /app

COPY --from=build /app/target/backend-0.0.1-SNAPSHOT.jar app.jar
COPY entrypoint.sh /entrypoint.sh

# Strip any CR characters a Windows checkout may have introduced — CRLF in the
# ENTRYPOINT script surfaces as a confusing "exec format error".
RUN sed -i 's/\r$//' /entrypoint.sh && chmod +x /entrypoint.sh \
    && chown -R pwuser:pwuser /app

# Drop root. Chromium refuses to start as root unless --no-sandbox is passed,
# and this app navigates to arbitrary user-supplied URLs (accessibility scans,
# crawler), so its renderer sandbox is a defense worth keeping rather than
# switching off. pwuser is provided by the Playwright base image.
#
# HOME must be set explicitly: Docker does NOT read it from /etc/passwd when
# USER changes, so it would otherwise stay /root — which pwuser cannot write,
# and Playwright Java needs a writable home for its driver bundle.
ENV HOME=/home/pwuser

USER pwuser

# 8080 = Spring Boot, 6080 = noVNC. Both stay on the compose network; only
# Caddy publishes anything to the host.
EXPOSE 8080 6080

ENTRYPOINT ["/entrypoint.sh"]