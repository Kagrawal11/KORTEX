# 15 — Configuration & Environment

All configuration facts below were read directly from `backend/src/main/resources/application.properties`, `backend/pom.xml`, `backend/src/main/java/com/miniautomation/backend/config/AsyncConfig.java`, `backend/src/main/java/com/miniautomation/backend/ai/LlmClient.java`, and the frontend's `package.json`/`vite.config.ts`/`src/services/*.ts` files, on the current state of the repository.

⚠️ **A real-looking plaintext database password is committed in `backend/src/main/resources/application.properties`** (`spring.datasource.password=...`). This is a tracked file in the repository. The value itself is withheld from this documentation. This should be treated as a genuine secret-hygiene issue independent of anything else in this document — see `22-LIMITATIONS.md` (Security).

---

## 1. Backend runtime

| Item | Value | Source |
|---|---|---|
| Language / JDK | Java 17 | `pom.xml` `<java.version>17</java.version>` |
| Framework | Spring Boot 3.3.2 (`spring-boot-starter-parent`) | `pom.xml` |
| Build tool | Maven (`mvnw`/`mvnw.cmd` wrapper present) | repo root |
| Server port | Not overridden — Spring Boot default **8080** | No `server.port` key exists in `application.properties`; confirmed empirically earlier in this project's development (backend responds on `http://localhost:8080`) |
| Application name | `mini-automation` | `spring.application.name=mini-automation` |

### Key dependencies and exact versions (from `pom.xml`)

| Dependency | Version | Used for |
|---|---|---|
| `com.microsoft.playwright:playwright` | 1.45.0 | Browser automation (recording/playback) AND API Testing's `APIRequestContext` HTTP execution |
| `com.deque.html.axe-core:playwright` | 4.10.1 | Accessibility scanning (axe-core engine, Playwright Java binding) |
| `org.jsoup:jsoup` | 1.17.2 | DOM structural analysis (Crawler module) |
| `org.apache.poi:poi-ooxml` | 5.2.5 | XLSX parsing (Data-Driven dataset upload) |
| `com.opencsv:opencsv` | 5.9 | CSV parsing (Data-Driven dataset upload) |
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox` | 1.1.73 | Server-side HTML→PDF rendering for Execution Reports |
| `com.jayway.jsonpath:json-path` | 2.9.0 | JSONPath evaluation — API Testing assertions and response-value extraction |
| `com.mysql:mysql-connector-j` | (managed by Spring Boot parent) | MySQL JDBC driver |
| `spring-boot-starter-web`, `-actuator`, `-data-jpa`, `-mail`, `-test` | (managed) | REST API, health/info endpoints, JPA/Hibernate, SMTP email, JUnit5+Mockito+AssertJ test stack |
| `jackson-databind` | (managed) | JSON serialization |

---

## 2. Database configuration (Required)

```
spring.datasource.url=jdbc:mysql://localhost:3306/mini_automation_db?createDatabaseIfNotExist=true&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=<withheld — see warning above>
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
```

- The database is auto-created (`createDatabaseIfNotExist=true`) but MySQL itself must already be running and reachable at `localhost:3306`. **This is hardcoded — there is no environment-variable override for the datasource URL/username/password**, unlike the mail configuration below. Changing the DB host/credentials requires editing this file directly.
- `spring.jpa.hibernate.ddl-auto=update` — schema is auto-migrated on every startup by Hibernate inspecting the entity classes; there is no separate migration tool (no Flyway/Liquibase dependency in `pom.xml`). This is a Development-oriented setting — it is generally considered unsafe for a real production database (Hibernate can alter/add columns automatically but will not safely handle destructive changes).
- `spring.jpa.show-sql=false` with `spring.jpa.properties.hibernate.format_sql=true` — SQL logging is off by default; formatting would only take effect if `show-sql` were turned on.
- `spring.jpa.open-in-view=false` — **this is architecturally significant**, not just a performance tweak. With Open Session In View disabled, any `@ManyToOne`/`@OneToMany` relation that must appear in a JSON response has to be `FetchType.EAGER` (or explicitly re-fetched) — several entities in this codebase are EAGER specifically because of this setting (see `10-DATABASE.md`).

---

## 3. Multipart file upload (Required for Data-Driven)

```
spring.servlet.multipart.max-file-size=50MB
spring.servlet.multipart.max-request-size=50MB
```
Governs the maximum size of an uploaded CSV/XLSX dataset file for Data-Driven test configuration.

---

## 4. Actuator (Optional / Development)

```
management.endpoints.web.exposure.include=health,info
```
Only `/actuator/health` and `/actuator/info` are exposed — no metrics, env, or beans endpoints are opened up.

---

## 5. LLM / AI configuration (Optional)

```
mini.automation.llm.api-key=
mini.automation.llm.endpoint=https://api.openai.com/v1/chat/completions
mini.automation.llm.model=gpt-4o-mini
```

Read via `@Value` injection in `backend/src/main/java/com/miniautomation/backend/ai/LlmClient.java`:
```
@Value("${mini.automation.llm.api-key:}")      private String apiKey;
@Value("${mini.automation.llm.endpoint:https://api.openai.com/v1/chat/completions}") private String endpoint;
@Value("${mini.automation.llm.model:gpt-4o-mini}") private String model;
```

- The default `mini.automation.llm.api-key=` is blank in the committed file — the comment directly above it in `application.properties` states: *"Leave api-key blank to use the built-in heuristic fallback (no LLM calls). Set to your OpenAI key to enable AI descriptions and self-healing."* `LlmClient` checks `apiKey != null && !apiKey.trim().isEmpty()` at two call sites (verified) before ever making an outbound HTTP call — with it blank, `AiElementResolver`'s self-healing chain and AI step-description generation both silently skip the LLM step entirely, logging `"No LLM API key configured"` (confirmed message text from a real run's log output).
- **Environment variable override**: `application.properties` does not use an explicit `${MINI_AUTOMATION_LLM_API_KEY}` placeholder for this key — but Spring Boot's standard relaxed environment-variable binding maps an OS environment variable named `MINI_AUTOMATION_LLM_API_KEY` onto the property `mini.automation.llm.api-key` automatically, and OS environment variables take precedence over `application.properties` values in Spring Boot's default property-source order. This is standard Spring Boot behavior (not itself visible as a line of code in this repo), so setting `MINI_AUTOMATION_LLM_API_KEY` as an environment variable before starting the backend is the supported way to enable this without editing the properties file. This matches how the codebase's own log/comment text refers to it elsewhere.
- Provider: OpenAI-compatible chat completions endpoint, default model `gpt-4o-mini`, default endpoint `https://api.openai.com/v1/chat/completions` — both are themselves overridable via the same properties/env mechanism.

---

## 6. Email / SMTP configuration (Optional — required only to use "Email Report")

```
spring.mail.host=${MINI_AUTOMATION_MAIL_HOST:}
spring.mail.port=${MINI_AUTOMATION_MAIL_PORT:587}
spring.mail.username=${MINI_AUTOMATION_MAIL_USERNAME:}
spring.mail.password=${MINI_AUTOMATION_MAIL_PASSWORD:}
spring.mail.properties.mail.smtp.auth=true
spring.mail.properties.mail.smtp.starttls.enable=true
spring.mail.properties.mail.smtp.connectiontimeout=8000
spring.mail.properties.mail.smtp.timeout=8000
mini.automation.mail.from=${MINI_AUTOMATION_MAIL_FROM:${MINI_AUTOMATION_MAIL_USERNAME:}}
```

This is the ONLY configuration block in the file that explicitly uses `${ENV_VAR:default}` Spring placeholder syntax — i.e. it is the one block of config self-documented as environment-variable-driven by design, not by Spring's implicit relaxed binding.

| Environment variable | Default if unset | Purpose |
|---|---|---|
| `MINI_AUTOMATION_MAIL_HOST` | *(empty)* | SMTP host, e.g. `smtp.gmail.com` |
| `MINI_AUTOMATION_MAIL_PORT` | `587` | SMTP port |
| `MINI_AUTOMATION_MAIL_USERNAME` | *(empty)* | SMTP auth username |
| `MINI_AUTOMATION_MAIL_PASSWORD` | *(empty)* | SMTP auth password/app-password |
| `MINI_AUTOMATION_MAIL_FROM` | falls back to `MINI_AUTOMATION_MAIL_USERNAME` | "From" address on sent report emails |

The in-file comment gives an explicit Gmail example (host `smtp.gmail.com`, port `587`, and a reminder to use an **App Password**, not the real account password). The comment also states the deliberate design: *"Leave the environment variables unset to leave email disabled — `ReportEmailService` detects that and returns a clear error instead of trying (and failing) to connect to an empty SMTP host."* (Not independently re-verified inside `ReportEmailService.java` for this document — see `12-REPORT-PDF-EMAIL.md` for that service's own deep-dive.)

STARTTLS is always enabled (`mail.smtp.starttls.enable=true`), auth is always required (`mail.smtp.auth=true`), and both connection and read timeouts are fixed at 8000ms (8 seconds) — not configurable via environment variable.

---

## 7. Async execution configuration (Required — no override)

`backend/src/main/java/com/miniautomation/backend/config/AsyncConfig.java`:
```java
@Bean(DD_TASK_EXECUTOR)   // DD_TASK_EXECUTOR = "ddTaskExecutor"
public Executor ddTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(4);
    executor.setQueueCapacity(50);
    executor.setThreadNamePrefix("dd-async-");
    executor.initialize();
    return executor;
}
```
This is a fixed, hardcoded thread pool (2 core / 4 max / 50 queued) — not exposed via `application.properties` or any environment variable. Its own class Javadoc is explicit about scope: it does **not** control the Playwright/browser thread (that is a single dedicated thread owned by `BrowserManager.runOnPlaywrightThread`); it only controls which thread runs the "fire the background run and return the HTTP response immediately" bookkeeping in classes like `TestRunAsyncExecutor`/`DataDrivenAsyncExecutor` before the actual browser work is handed off to `BrowserManager`'s pinned thread. `@EnableAsync` is declared on this same class, so this is the only place `@Async` behavior for the whole app is configured.

---

## 8. Browser / Playwright configuration (hardcoded, not exposed via properties)

Verified in `backend/src/main/java/com/miniautomation/backend/browser/BrowserManager.java`, `ensureSessionAlive()`:
```java
browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
        .setHeadless(false)
        .setSlowMo(50)
        .setArgs(java.util.List.of("--disable-gpu", "--disable-gpu-compositing")));
```
- Always headed (visible) Chromium — `setHeadless(false)` is not configurable via any property.
- 50ms slow-motion on every Playwright-driven action.
- `--disable-gpu` / `--disable-gpu-compositing` launch args — added to mitigate a real, observed window-flicker issue on this development machine (GPU compositor rendering under headed Chromium on Windows); not conditional on OS or environment.
- None of these three settings have an environment-variable or properties-file override — changing them requires editing `BrowserManager.java` directly.

Accessibility scanning and API Testing use their own, separate Playwright browser/context lifecycles (see `08-ACCESSIBILITY.md` and `09-API-TESTING.md`) — **not verified in this document** whether they share any of these same launch options; each module's own executor should be checked directly.

---

## 9. Frontend configuration

### Build/dev tooling (`frontend_FIXED_v2/frontend/package.json`)

| Item | Value |
|---|---|
| React | `^19.2.8` |
| React DOM | `^19.2.8` |
| react-router-dom | `^7.18.2` |
| axios | `^1.19.0` |
| lucide-react (icons) | `^1.31.0` |
| TypeScript | `~6.0.2` |
| Vite | `^8.2.0` |
| Lint | `oxlint` (script: `npm run lint`) |
| Dev server | `npm run dev` → `vite` (default Vite dev port **5173**, not overridden) |
| Build | `npm run build` → `tsc -b && vite build` |
| Test runner | **None configured** — no `vitest`/`jest`/`@testing-library/*` dependency exists, and there is no `"test"` script in `package.json` |

`vite.config.ts` is minimal — `defineConfig({ plugins: [react()] })` only. **No dev-server proxy to the backend is configured**, and no `import.meta.env`/`VITE_*` environment variable is read anywhere in `src/services/`.

### Backend base URL — hardcoded, not environment-driven

Verified via direct grep of every service file in `frontend_FIXED_v2/frontend/src/services/`:

| File | Constant | Value |
|---|---|---|
| `uiAutomationApi.ts` | `API_BASE_URL` | `http://localhost:8080/api/ui-automation` |
| `accessibilityApi.ts` | `ACCESSIBILITY_API_BASE_URL` | `http://localhost:8080/api/accessibility` |
| `apiTestingApi.ts` | `BASE` | `http://localhost:8080/api/api-testing` |
| `reportEmailApi.ts` | `REPORTS_API_BASE_URL` | `http://localhost:8080/api/reports` |

All four are literal string constants — **there is no single shared base-URL constant, no `.env` file, and no build-time environment variable substitution for any of them.** Pointing the frontend at a non-localhost or differently-ported backend requires editing all four files directly. This is a real, verified limitation — see `22-LIMITATIONS.md`.

---

## 10. Static assets

`backend/src/main/resources/branding/kortex_logo_small.png` and `backend/src/main/resources/fonts/Roboto-Regular.ttf` (+ `Roboto-OFL-LICENSE.txt`) are bundled backend resources — used by `ReportPdfService` for PDF branding/typography (see `12-REPORT-PDF-EMAIL.md`; not independently re-verified in this document beyond confirming the files exist at these paths).

---

## Summary: Required vs Optional

| Required to start the backend at all | Optional (feature degrades gracefully if unset) |
|---|---|
| A running MySQL instance at `localhost:3306` matching the hardcoded credentials | LLM API key (self-healing/AI descriptions silently skip, confirmed via `LlmClient`'s blank-check) |
| Java 17 runtime | SMTP host/credentials (Email Report disabled with a clear error, per `application.properties`'s own comment — not independently re-verified in `ReportEmailService.java` for this document) |
| | Everything else in this document is either hardcoded (browser launch flags, async pool size, backend base URLs in the frontend) or has a working default (mail port 587, LLM endpoint/model, actuator exposure) |

## Not verified from source
- Exact behavior of `ReportEmailService` when SMTP env vars are unset (the "clear error instead of trying" claim is from the properties file's own comment, not independently traced through `ReportEmailService.java` in this document — see `12-REPORT-PDF-EMAIL.md`).
- Whether Accessibility scanning or API Testing's Playwright sessions reuse `BrowserManager`'s launch options or configure their own independently.
- Any CI/CD environment configuration (no CI pipeline files were located under a standard path during this survey; a `ci-tools/` directory exists at the repo root but was not opened for this document).
- Production deployment configuration (Docker, systemd, reverse proxy, TLS termination) — no such files were found during this survey.
