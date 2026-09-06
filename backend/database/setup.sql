-- ============================================================================
-- Mini Automation — Database Setup Script
-- ============================================================================
--
-- What this is
-- ------------
-- A complete, from-scratch MySQL setup for Mini Automation: creates the
-- database and every table the backend needs (UI Automation, Data Driven
-- Testing, and Accessibility Testing), with the exact columns, keys, and
-- foreign keys that the application's JPA entities define.
--
-- Do I actually need to run this?
-- --------------------------------
-- Not strictly. The backend's `src/main/resources/application.properties`
-- has:
--   spring.jpa.hibernate.ddl-auto=update
--   spring.datasource.url=jdbc:mysql://localhost:3306/mini_automation_db?createDatabaseIfNotExist=true&...
-- which means Hibernate will create the database and every table below
-- automatically the first time you start the backend against a MySQL
-- server it can reach. Run this script instead of relying on that when you
-- want:
--   - an explicit, reviewable schema (e.g. before a demo, or in an
--     environment where auto-DDL is turned off),
--   - a fast local setup without waiting on the backend's first boot,
--   - a readable reference for the app's actual data model.
--
-- How to run it
-- --------------
--   1. Install MySQL 8.x and make sure it's running on localhost:3306.
--   2. From a terminal:
--        mysql -u root -p < setup.sql
--      (enter your MySQL root password when prompted)
--   3. Make sure backend/src/main/resources/application.properties has a
--      matching spring.datasource.username / spring.datasource.password
--      for your MySQL install (defaults to root / a password you set
--      locally — this script does not set or change any MySQL user
--      password; it only creates the schema).
--   4. Start the backend as usual (mvnw spring-boot:run) — Hibernate will
--      see the tables already exist and leave them alone (ddl-auto=update
--      only ADDS missing columns/tables, it never drops existing ones).
--
-- Re-running this script
-- -----------------------
-- Safe to re-run on a fresh/empty setup. On an EXISTING database with real
-- data, the DROP TABLE statements below will permanently delete everything
-- in these 8 tables. If you already have test history you want to keep,
-- do not re-run this script — just let ddl-auto=update manage schema
-- changes going forward instead.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1. Database
-- ----------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS mini_automation_db
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_general_ci;

USE mini_automation_db;


-- ----------------------------------------------------------------------------
-- 2. (Optional) Dedicated application user
-- ----------------------------------------------------------------------------
-- The backend ships configured to connect as `root` (see application.properties).
-- That's fine for a single-developer local setup. If you'd rather not use the
-- MySQL root account for the app, uncomment and adapt this block, then update
-- spring.datasource.username/password to match.
--
-- CREATE USER IF NOT EXISTS 'mini_automation'@'localhost' IDENTIFIED BY 'change-me';
-- GRANT ALL PRIVILEGES ON mini_automation_db.* TO 'mini_automation'@'localhost';
-- FLUSH PRIVILEGES;


-- ----------------------------------------------------------------------------
-- 3. Fresh setup — drop existing tables (child tables first)
-- ----------------------------------------------------------------------------
-- FOREIGN_KEY_CHECKS is disabled around the drop/create block purely so the
-- statements below don't have to be ordered perfectly around FK dependencies;
-- it's re-enabled at the very end.
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS test_run_steps;
DROP TABLE IF EXISTS test_runs;
DROP TABLE IF EXISTS test_steps;
DROP TABLE IF EXISTS data_driven_row_results;
DROP TABLE IF EXISTS data_driven_runs;
DROP TABLE IF EXISTS test_scenarios;
DROP TABLE IF EXISTS accessibility_scan_runs;
DROP TABLE IF EXISTS accessibility_scans;


-- ============================================================================
-- UI AUTOMATION
-- ============================================================================

-- A recorded UI Automation test — a name, a target URL, and the steps
-- captured while recording. One row per test created via "Create Test".
CREATE TABLE test_scenarios (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    name        VARCHAR(255) NULL,
    target_url  VARCHAR(255) NULL,
    created_at  DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One recorded browser action (click/type/keydown/...) belonging to a test
-- scenario, in the order it was captured. Consumed by both standard playback
-- and Data-Driven execution (which loops over a sub-range of these steps).
CREATE TABLE test_steps (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    step_order       INT NOT NULL,
    action_type      VARCHAR(255) NULL,
    primary_selector VARCHAR(255) NULL,
    element_id       VARCHAR(255) NULL,
    name             VARCHAR(255) NULL,
    type             VARCHAR(255) NULL,
    tag              VARCHAR(255) NULL,
    step_key         VARCHAR(255) NULL,
    role             VARCHAR(255) NULL,
    label_text       VARCHAR(255) NULL,
    text             VARCHAR(255) NULL,
    placeholder      VARCHAR(255) NULL,
    input_value      VARCHAR(2000) NULL,
    ai_description   VARCHAR(1000) NULL,
    test_id          VARCHAR(255) NULL,
    aria_label       VARCHAR(255) NULL,
    frame_selector   VARCHAR(255) NULL,
    scenario_id      BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_test_steps_scenario_id (scenario_id),
    CONSTRAINT fk_test_steps_scenario
        FOREIGN KEY (scenario_id) REFERENCES test_scenarios (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One standard ("Run Test") execution of a test scenario.
CREATE TABLE test_runs (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    scenario_id        BIGINT NULL,
    status             VARCHAR(255) NULL COMMENT 'RUNNING / PASSED / FAILED',
    started_at         DATETIME(6) NULL,
    completed_at       DATETIME(6) NULL,
    total_duration_ms  BIGINT NOT NULL,
    total_steps        INT NOT NULL,
    passed_steps       INT NOT NULL,
    failed_steps       INT NOT NULL,
    healed_by_ai_steps INT NOT NULL,
    error_message      TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_test_runs_scenario_id (scenario_id),
    CONSTRAINT fk_test_runs_scenario
        FOREIGN KEY (scenario_id) REFERENCES test_scenarios (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per-step result for one standard test run (status, duration, error, the
-- selector actually used — including any self-healed selector).
CREATE TABLE test_run_steps (
    id               BIGINT NOT NULL AUTO_INCREMENT,
    test_run_id      BIGINT NULL,
    step_order       INT NOT NULL,
    action_type      VARCHAR(255) NULL,
    primary_selector TEXT NULL,
    input_value      TEXT NULL,
    status           VARCHAR(255) NULL COMMENT 'PASSED / FAILED / HEALED_BY_AI / SKIPPED',
    error_message    TEXT NULL,
    duration_ms      BIGINT NOT NULL,
    PRIMARY KEY (id),
    KEY idx_test_run_steps_test_run_id (test_run_id),
    CONSTRAINT fk_test_run_steps_test_run
        FOREIGN KEY (test_run_id) REFERENCES test_runs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ============================================================================
-- DATA DRIVEN TESTING
-- ============================================================================

-- One execution of a test scenario's steps looped over an uploaded
-- CSV/XLSX dataset (one iteration per data row).
CREATE TABLE data_driven_runs (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    scenario_id        BIGINT NOT NULL,
    status             VARCHAR(255) NULL COMMENT 'RUNNING / PASSED / FAILED',
    start_step_order   INT NOT NULL,
    end_step_order     INT NOT NULL,
    dataset_filename   VARCHAR(255) NULL,
    total_rows         INT NOT NULL,
    passed_rows        INT NOT NULL,
    failed_rows        INT NOT NULL,
    total_steps        INT NOT NULL,
    passed_steps       INT NOT NULL,
    failed_steps       INT NOT NULL,
    healed_by_ai_steps INT NOT NULL,
    total_duration_ms  BIGINT NOT NULL,
    error_message      TEXT NULL,
    dry_run            BIT(1) NOT NULL,
    started_at         DATETIME(6) NULL,
    completed_at       DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_data_driven_runs_scenario_id (scenario_id),
    CONSTRAINT fk_data_driven_runs_scenario
        FOREIGN KEY (scenario_id) REFERENCES test_scenarios (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Per-data-row result for one Data Driven run: pass/fail, which step it
-- failed at (if any), and the row's own input data / step results as JSON
-- (parsed client-side by the Data-Driven Report page).
CREATE TABLE data_driven_row_results (
    id                   BIGINT NOT NULL AUTO_INCREMENT,
    data_driven_run_id   BIGINT NULL,
    `row_number`         INT NULL,
    status               VARCHAR(255) NULL COMMENT 'SUCCESS / FAILED / CRITICAL_FAILURE',
    duration_ms          BIGINT NOT NULL,
    failed_at_step       INT NOT NULL COMMENT '-1 if every step in the row passed',
    error_message        TEXT NULL,
    row_data_json        TEXT NULL,
    step_results_json    TEXT NULL,
    PRIMARY KEY (id),
    KEY idx_data_driven_row_results_run_id (data_driven_run_id),
    CONSTRAINT fk_data_driven_row_results_run
        FOREIGN KEY (data_driven_run_id) REFERENCES data_driven_runs (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


-- ============================================================================
-- ACCESSIBILITY TESTING
-- ============================================================================

-- A saved accessibility scan configuration (target URL, scope, WCAG/best-
-- practice standards to check). Reusable — "Re-run Scan" starts a new run
-- against the same config.
CREATE TABLE accessibility_scans (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    name            VARCHAR(255) NULL,
    description     TEXT NULL,
    target_url      VARCHAR(255) NULL,
    scan_scope      VARCHAR(255) NULL COMMENT 'FULL_PAGE or SELECTOR',
    selector        TEXT NULL COMMENT 'Only set when scan_scope = SELECTOR',
    standards_json  TEXT NULL COMMENT 'e.g. ["WCAG_A","WCAG_AA","BEST_PRACTICES"]',
    created_at      DATETIME(6) NULL,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One executed accessibility scan (one real axe-core analysis run). Full
-- violation/incomplete detail and a passed-rules summary are stored as JSON
-- (parsed client-side by the Accessibility Report page) rather than
-- normalised child tables — this is read-only report detail, never queried
-- relationally.
CREATE TABLE accessibility_scan_runs (
    id                    BIGINT NOT NULL AUTO_INCREMENT,
    scan_id               BIGINT NULL,
    status                VARCHAR(255) NULL COMMENT 'RUNNING / COMPLETED / FAILED',
    target_url            VARCHAR(255) NULL COMMENT 'Snapshot of the scan target URL at run time',
    started_at            DATETIME(6) NULL,
    completed_at          DATETIME(6) NULL,
    duration_ms           BIGINT NOT NULL,
    error_message         TEXT NULL,
    total_violations      INT NOT NULL,
    critical_count        INT NOT NULL,
    serious_count         INT NOT NULL,
    moderate_count        INT NOT NULL,
    minor_count           INT NOT NULL,
    needs_review_count    INT NOT NULL,
    passed_count          INT NOT NULL,
    violations_json       LONGTEXT NULL,
    incomplete_json       LONGTEXT NULL,
    passes_summary_json   LONGTEXT NULL,
    PRIMARY KEY (id),
    KEY idx_accessibility_scan_runs_scan_id (scan_id),
    CONSTRAINT fk_accessibility_scan_runs_scan
        FOREIGN KEY (scan_id) REFERENCES accessibility_scans (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;


SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================================
-- Done. 8 tables created:
--   test_scenarios, test_steps, test_runs, test_run_steps            (UI Automation)
--   data_driven_runs, data_driven_row_results                        (Data Driven)
--   accessibility_scans, accessibility_scan_runs                     (Accessibility)
--
-- Next step: start the backend (from backend/: mvnw spring-boot:run) and the
-- frontend (from frontend_FIXED_v2/frontend/: npm install && npm run dev).
-- ============================================================================
