# KORTEX — ₹0 Deployment Runbook

Deploying KORTEX to a permanently-free stack with **every feature working**,
including live Record & Play.

---

## 0. Decisions

| Concern | Choice | Cost |
|---|---|---|
| Compute | GCP Compute Engine **e2-micro**, Always Free, `us-central1` | ₹0 forever |
| OS / disk | Debian 12, **30 GB pd-standard**, Standard network tier | ₹0 |
| Public IP | Static external IPv4, attached | ₹0 (free-tier VMs aren't charged for it) |
| Database | **Aiven for MySQL, free plan** (1 GB, no card) | ₹0 forever |
| Hostname | **DuckDNS** subdomain | ₹0 |
| TLS | **Caddy** + Let's Encrypt, auto-renew | ₹0 |
| Images | GitHub Actions → **GHCR** (public repo) | ₹0 |
| Interactive browser | **Xvfb + x11vnc + openbox + noVNC** in the backend image | ₹0 |

The only thing that can ever cost money is GCP egress above 1 GB/month — see §9.

### Why not the alternatives

| Option | Verdict |
|---|---|
| Render free | 512 MB / 0.1 CPU — Chromium won't fit or run. Spins down at 15 min idle, which kills in-flight `@Async` runs. Free Postgres expires after 30 days. |
| Cloud Run / Azure Container Apps | CPU is throttled to ~0 after the HTTP response returns, and KORTEX does *all* its work after the response returns. Disabling throttling blows the monthly vCPU-second grant in days. |
| AWS | New accounts get credits, not 750h EC2. Not permanent ₹0. |
| Azure B1S | 12 months only, then billed. |
| Railway / Fly.io | Trial credit / no free allowance. |
| Hugging Face Spaces | Creating a Docker Space now requires a paid plan. |
| Postgres (Neon/Supabase) instead of MySQL | 6 entities use `columnDefinition = "LONGTEXT"`, which is not valid Postgres. With `ddl-auto=update` Hibernate fails at startup. Staying on MySQL = zero entity changes. |

---

## 1. Architecture

```
Your browser
    │ HTTPS
    ▼
Caddy :443 ── Let's Encrypt ── basic auth (ONE gate for everything)
    │
    ▼
frontend (nginx)
    ├─ /        SPA (React build)
    ├─ /api/  ──▶ backend:8080   Spring Boot
    └─ /vnc/  ──▶ backend:6080   websockify + noVNC client
                      │
                      ▼
                  x11vnc :5900 (-localhost, never published)
                      │
                      ▼
                  Xvfb :99 + openbox
                      │
                      ▼
                  Chromium (Playwright, headed)
                      ▲
                      │ same container
                  Spring Boot ──TLS──▶ Aiven MySQL
```

Only ports **80 and 443** are published. 8080, 6080 and 5900 exist solely on
the internal compose network.

### Why noVNC, and why the recording code didn't change

`RecordingSession.startRecording()` opens a real Chromium on the server,
injects DOM listeners, and then waits for **a human to click in that window**.
Steps only exist because those listeners fire.

The old implementation exposed a read-only JPEG polled every 1.5 s. There was
no input path at all, so on any remote host recording produced a scenario with
**zero steps** — silently, without an error.

A click delivered over VNC is a *real* X input event, so Chromium synthesizes
genuinely trusted DOM events and the injected listeners fire exactly as they do
locally. That is why `RecordingSession`, `EventListenerInjector`,
`ElementMetadataExtractor`, `PlaybackEngine` and every entity are **untouched**
by this deployment work. The alternative — building CDP
`Input.dispatchMouseEvent` forwarding endpoints in Java — would have been ~400
lines reimplementing VNC badly.

It also means `MfaPauseDetector` and `CaptchaPauseDetector` now work as
designed: a human can complete the OTP in the visible browser.

---

## 2. Feature matrix after deployment

| Feature | Status | Notes |
|---|---|---|
| **Record** | ✅ | Driven through noVNC |
| **Play** | ✅ | Headed Chromium on Xvfb |
| MFA / CAPTCHA pauses | ✅ | Human can act in the VNC view |
| Accessibility scans (axe-core) | ✅ | Headless |
| API Testing + Postman/OpenAPI import | ✅ | `APIRequestContext`, no browser |
| Data-Driven (CSV/XLSX) | ✅ | Playback engine under Xvfb |
| Dashboard / history / reports | ✅ | |
| PDF + email delivery | ✅ | SMTP :587 — GCP blocks :25 only |
| Self-healing locators, script export | ✅ | |

**One caveat, by design:** `BrowserManager` holds exactly one browser on one
pinned thread, and there is one X display. **One person records or plays at a
time.** Concurrent users share the same mouse. Fine for single-user/demo;
per-session browsers is a feature, not a config change.

---

## 3. What changed in the repo

Nothing in the recording or playback *logic*.

| File | Change |
|---|---|
| `backend/Dockerfile` | Xvfb/x11vnc/openbox/noVNC, fixed display :99, drops to `pwuser` |
| `backend/entrypoint.sh` | **new** — starts the display stack, then the JVM |
| `backend/.gitattributes` | `*.sh text eol=lf` so a Windows clone can't break the entrypoint |
| `config/AsyncConfig.java` | pool 2/4 → **1/1** (see §8) |
| `controller/UIAutomationController.java` | removed `/recording/screenshot` |
| `browser/BrowserManager.java` | removed `takeScreenshot()` |
| `crawler/HtmlFetcher.java` | removed the `page.html` debug dump |
| `services/uiAutomationApi.ts` | `recordingScreenshotUrl()` → `RECORDING_VNC_URL` |
| `pages/RecordingWorkspace.tsx` | polled `<img>` → live noVNC `<iframe>`; poll deleted |
| `frontend/nginx.conf` | `/vnc/` websocket proxy; `client_max_body_size 50m` |
| `docker-compose.yml` | Aiven instead of local MySQL; Caddy edge; no published app ports |
| `Caddyfile` | **new** — TLS + basic auth |
| `.env.example` | full variable set |
| `.github/workflows/build.yml` | **new** — builds both images to GHCR |

`application.properties` is deliberately unchanged: every value is already
env-overridable, and Spring's relaxed binding maps `MINI_AUTOMATION_MAIL_HOST`
→ `mini.automation.mail.*` with env vars outranking the file.

`client_max_body_size 50m` was a latent bug, not a new requirement:
`spring.servlet.multipart.max-file-size` is 50 MB, but nginx's 1 MB default
would have rejected Data-Driven dataset uploads with a 413 before Spring ever
saw them.

---

## 4. Step 1 — Accounts (~20 min)

1. **GCP** — create account, enable billing with a card. Free-tier resources
   stay free; overage bills.
   - Immediately: **Billing → Budgets → new budget, amount ₹1, alerts at
     50/90/100%.** Do this before creating anything.
2. **Aiven** — sign up (no card) → *Create service* → **MySQL** → **Free
   plan** → region **closest to `us-central1`**, not closest to you: the chatty
   traffic is VM↔DB, not you↔DB.
   - Copy host, port, user (`avnadmin`), password, database (`defaultdb`).
3. **GitHub** — the repo must be **public** for free Actions minutes and free
   GHCR storage.

> **Before making the repo public:** `application.properties` previously
> contained a real database password. It is removed from the working tree but
> **still in git history** (commit `563a541` and earlier). Rotate that password
> and consider rewriting history, or the repo will leak it the moment it goes
> public.

## 5. Step 2 — Build the images

Push to `main`. The workflow builds `backend` and `frontend` and pushes to
`ghcr.io/<owner>/kortex-backend:latest` and `kortex-frontend:latest`.

After the first successful run, go to each package on GitHub → *Package
settings* → **change visibility to Public**, otherwise the VM's `docker pull`
needs credentials.

> The e2-micro cannot build these images — `mvn package` plus `npm ci` will
> OOM 1 GB. That is why `docker-compose.yml` has no `build:` stanza. The VM
> only ever pulls.

## 6. Step 3 — Provision the VM (~15 min)

```
Region:   us-central1  (or us-west1 / us-east1 — nowhere else qualifies)
Machine:  e2-micro     (NOT e2-small — that bills)
Boot:     Debian 12, 30 GB, pd-standard  (NOT balanced/SSD — those bill)
Network:  Standard tier; firewall: allow HTTP + HTTPS only
IP:       reserve a static external IP, keep it attached
```

Exactly **one** e2-micro across the whole billing account qualifies as free.

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER && newgrp docker

# Load-bearing, not insurance — see §8
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab

sudo apt install -y unattended-upgrades
```

## 7. Step 4 — Hostname, TLS, deploy

Register a DuckDNS subdomain and point it at the static IP. Caddy obtains the
Let's Encrypt certificate on first boot over HTTP-01 on port 80 and renews it
forever. (`duckdns.org` is on the Public Suffix List, so your subdomain gets
its own rate-limit quota — non-issue.)

```bash
mkdir -p /opt/kortex && cd /opt/kortex
# copy docker-compose.yml, Caddyfile and .env here
chmod 600 .env

docker compose pull
docker compose up -d --no-build
docker compose logs -f backend
```

Generate the basic-auth hash:

```bash
docker run --rm caddy:2-alpine caddy hash-password --plaintext 'your-password'
```

The bcrypt output contains `$` characters. After starting the stack, confirm it
survived into the container verbatim:

```bash
docker compose exec caddy env | grep KORTEX_BASIC_AUTH_HASH
```

All environment variables are documented in `.env.example`.

---

## 8. Step 5 — Verification, in order

| # | Check | Proves |
|---|---|---|
| 1 | `curl -u kortex:PASS https://<host>/actuator/health` → `{"status":"UP"}` | app + TLS + auth |
| 2 | Backend log: Hibernate schema update, no `LONGTEXT` errors | Aiven MySQL wired |
| 3 | Aiven console → 14 tables present | `ddl-auto=update` ran |
| 4 | `docker compose exec backend xdpyinfo -display :99` | virtual display up |
| 5 | Open `https://<host>/vnc/vnc.html` → grey openbox desktop | Xvfb → x11vnc → websockify → nginx → Caddy chain |
| 6 | New Accessibility Scan on `https://example.com` → COMPLETED with violations | headless Chromium + axe-core |
| 7 | API Testing → collection → one GET → Run → passes | `APIRequestContext` |
| 8 | Start a recording → Chromium appears **inside** the noVNC view | Playwright targeting `:99` |
| 9 | **Click a button in the VNC view → backend log prints `[RecordingSession] ✔ RAW event: type=click selector=…`** | **record works end to end** |
| 10 | Stop & Save → scenario has **> 0 steps** | capture → dedup → persist |
| 11 | Play it back → steps execute, visible in the same view | headed playback under Xvfb |
| 12 | Data-Driven run with a 3-row CSV | POI/OpenCSV + playback + multipart |
| 13 | Report → Email Report → arrives | SMTP :587 + openhtmltopdf |
| 14 | `free -h` during #11 — swap in use is normal, an OOM-kill is not | memory budget holds |

**Step 9 is the decisive one.** If that log line appears, record-and-play works
on the deployed instance.

### If the backend container exits immediately

- `exec format error` → `entrypoint.sh` has CRLF endings. The Dockerfile strips
  them and `.gitattributes` prevents them; if you added the file by hand, run
  `dos2unix backend/entrypoint.sh`.
- `unknown user pwuser` at build time → the base image tag changed. Either
  remove the `USER pwuser` / `chown` lines and add `--no-sandbox` to the
  `setArgs(...)` call in `BrowserManager.java:371` and to
  `AccessibilityScanExecutor.java:53`, or pin a tag that has `pwuser`.
- `Running as root without --no-sandbox is not supported` → the `USER pwuser`
  line was dropped. Restore it rather than adding `--no-sandbox`; this app
  navigates to arbitrary user-supplied URLs and the renderer sandbox matters.

---

## 9. Memory budget

| | |
|---|---|
| OS + Docker | ~200 MB |
| JVM (`-Xmx320m`) | ~420 MB |
| Chromium | ~450 MB |
| Xvfb + x11vnc + openbox + websockify | ~100 MB |
| **Total** | **~1170 MB vs 1024 MB available** |

The 4 GB swapfile absorbs the gap. This is why `AsyncConfig` went to 1/1:
`AccessibilityScanExecutor` launches its **own** Chromium per scan, so a pool
of 4 meant up to 5 concurrent browsers. Runs now queue instead of racing —
same results, one at a time.

Recording feels sluggish on the 0.25-vCPU baseline, but e2-micro bursts to
2 vCPU and recording sessions last minutes, so burst credits cover the real
pattern.

## 10. Egress — the only thing that can cost money

| | |
|---|---|
| GCP Always Free | **1 GB/month** outbound |
| noVNC, actively interacting @1280×900, quality 3 | ~80–200 KB/s |
| noVNC **idle** | **~0** — VNC is delta-based |
| → free allowance | **~1.5–3.5 hours of active recording per month** |
| Overage | ~$0.12/GB → 10 GB ≈ ₹110/month |

Deleting the 1.5 s poll is a real win here, not just a cleanup: the old `<img>`
re-sent a full ~80 KB JPEG **every 1.5 s whether or not anything had changed**
— roughly 200 MB/hour even on a completely static page. VNC sends nothing while
the screen is static.

**The one trap:** an open noVNC tab on a page with a carousel, spinner or
autoplaying video streams continuously even when nobody is touching it. That is
what silently eats the GB. Close the recording tab when you are done.

Levers if you need more: drop `SCREEN_GEOMETRY` to `1024x768x24` (−35% pixels)
and `quality=2` in `RECORDING_VNC_URL` (`services/uiAutomationApi.ts`).

Also counted as egress: VM↔Aiven JDBC traffic. Small but not zero —
`AccessibilityScanRunEntity` writes full axe-core JSON into three `LONGTEXT`
columns. Aiven's free plan caps at 1 GB storage, so prune old runs periodically;
that is what will fill it, not row count.

## 11. Security

| Item | Status |
|---|---|
| Caddy `basic_auth` on everything | **Mandatory.** KORTEX has no Spring Security and `@CrossOrigin(origins="*")` on all 8 controllers. Exposed without it, anyone can POST an arbitrary URL to `/api/accessibility/scans` and use the deployment as a free SSRF/crawling proxy billed to your account. |
| Chromium renderer sandbox | Kept — container drops to `pwuser` instead of using `--no-sandbox` |
| x11vnc `-localhost` + `-nopw` | Safe **only** together — unreachable except through the authenticated proxy |
| 5900 / 6080 / 8080 never published | `expose`, not `ports` |
| GCP firewall | 80, 443 only |
| `.env` | `chmod 600`, gitignored |
| SSH | keys only, via GCP OS Login |
| DB password in git history | **Rotate before making the repo public** — see §4 |
| `@CrossOrigin("*")` | left as-is: same-origin through nginx means it is never exercised |
| `setIgnoreHTTPSErrors(true)` (`ApiHttpExecutor:66`) | intentional and correct for a tool that tests staging hosts |

## 12. Total cost

**₹0/month**, provided: ≤1 GB egress, `pd-standard` boot disk, `e2-micro`, one
of the three free regions, public GitHub repo. The ₹1 budget alert from §4
catches all four if one slips.

If the box ever proves too small, the same `docker-compose.yml` runs unchanged
on any ~₹350/month VPS. That is the upgrade path — not a redesign.
