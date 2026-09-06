#!/usr/bin/env node
/*
 * Kortex Accessibility CI Gate
 *
 * Starts a Kortex accessibility scan against a target URL via the existing
 * REST API, waits for it to finish, and exits non-zero if the result
 * breaches configured severity thresholds — so a CI pipeline can fail the
 * build on new/existing accessibility violations.
 *
 * Requires a reachable, already-running Kortex backend. This script only
 * calls the existing /api/accessibility/* endpoints; it does not start the
 * backend itself.
 *
 * Usage:
 *   node accessibility-gate.js --target https://staging.example.com [options]
 *
 * Options:
 *   --api <url>            Kortex backend base URL          (default: http://localhost:8080)
 *   --target <url>         Page to scan                     (required)
 *   --name <text>          Scan name shown in Kortex         (default: "CI accessibility gate")
 *   --scope <FULL_PAGE|SELECTOR>                             (default: FULL_PAGE)
 *   --selector <css>       Required when --scope=SELECTOR
 *   --standards <list>     Comma-separated: WCAG_A,WCAG_AA,BEST_PRACTICES (default: WCAG_A,WCAG_AA)
 *   --max-critical <n>     Fail if critical violations exceed this         (default: 0)
 *   --max-serious <n>      Fail if serious violations exceed this          (default: 0)
 *   --timeout <seconds>    Max time to wait for the scan to finish         (default: 120)
 *   --poll-interval <sec>  How often to poll run status                    (default: 3)
 *
 * Exit codes:
 *   0  scan completed within thresholds
 *   1  scan completed but breached a threshold, scan failed, or timed out
 *   2  usage/network error (bad arguments, couldn't reach the API, etc.)
 *
 * Example (GitHub Actions step):
 *   - name: Accessibility gate
 *     run: node ci-tools/accessibility-gate.js --api ${{ vars.KORTEX_API_URL }} --target https://staging.example.com --max-critical 0 --max-serious 2
 */

function parseArgs(argv) {
  const args = {
    api: 'http://localhost:8080',
    target: null,
    name: 'CI accessibility gate',
    scope: 'FULL_PAGE',
    selector: null,
    standards: 'WCAG_A,WCAG_AA',
    maxCritical: 0,
    maxSerious: 0,
    timeoutSeconds: 120,
    pollIntervalSeconds: 3,
  };

  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    const next = () => argv[++i];
    switch (arg) {
      case '--api': args.api = next(); break;
      case '--target': args.target = next(); break;
      case '--name': args.name = next(); break;
      case '--scope': args.scope = next(); break;
      case '--selector': args.selector = next(); break;
      case '--standards': args.standards = next(); break;
      case '--max-critical': args.maxCritical = Number(next()); break;
      case '--max-serious': args.maxSerious = Number(next()); break;
      case '--timeout': args.timeoutSeconds = Number(next()); break;
      case '--poll-interval': args.pollIntervalSeconds = Number(next()); break;
      case '--help':
      case '-h':
        printUsage();
        process.exit(0);
        break;
      default:
        console.error(`[a11y-gate] Unknown argument: ${arg}`);
        printUsage();
        process.exit(2);
    }
  }

  if (!args.target) {
    console.error('[a11y-gate] Missing required --target <url>');
    printUsage();
    process.exit(2);
  }
  if (args.scope === 'SELECTOR' && !args.selector) {
    console.error('[a11y-gate] --scope=SELECTOR requires --selector <css>');
    process.exit(2);
  }
  if (typeof fetch !== 'function') {
    console.error('[a11y-gate] This script needs Node 18+ (native fetch). Please upgrade your CI runner\'s Node version.');
    process.exit(2);
  }

  return args;
}

function printUsage() {
  console.log([
    'Usage: node accessibility-gate.js --target <url> [options]',
    '',
    'Options:',
    '  --api <url>            Kortex backend base URL          (default: http://localhost:8080)',
    '  --target <url>         Page to scan                     (required)',
    '  --name <text>          Scan name shown in Kortex         (default: "CI accessibility gate")',
    '  --scope <FULL_PAGE|SELECTOR>                             (default: FULL_PAGE)',
    '  --selector <css>       Required when --scope=SELECTOR',
    '  --standards <list>     Comma list: WCAG_A,WCAG_AA,BEST_PRACTICES (default: WCAG_A,WCAG_AA)',
    '  --max-critical <n>     Fail if critical violations exceed this   (default: 0)',
    '  --max-serious <n>      Fail if serious violations exceed this    (default: 0)',
    '  --timeout <seconds>    Max time to wait for scan completion      (default: 120)',
    '  --poll-interval <sec>  How often to poll run status              (default: 3)',
  ].join('\n'));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function startScan(args, standards) {
  const res = await fetch(`${args.api}/api/accessibility/scans`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      name: args.name,
      description: 'Started by accessibility-gate.js CI script',
      targetUrl: args.target,
      scanScope: args.scope,
      selector: args.selector,
      standards,
    }),
  });
  if (!res.ok) {
    throw new Error(`Failed to start scan: HTTP ${res.status} ${await res.text()}`);
  }
  return res.json();
}

async function pollRun(args, runId) {
  const res = await fetch(`${args.api}/api/accessibility/runs/${runId}`);
  if (!res.ok) {
    throw new Error(`Failed to poll run status: HTTP ${res.status} ${await res.text()}`);
  }
  return res.json();
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const standards = args.standards.split(',').map((s) => s.trim()).filter(Boolean);

  console.log(`[a11y-gate] Starting scan of ${args.target} via ${args.api} (standards: ${standards.join(', ')})...`);

  let run;
  try {
    run = await startScan(args, standards);
  } catch (e) {
    console.error(`[a11y-gate] Could not start scan: ${e.message}`);
    process.exit(2);
  }

  console.log(`[a11y-gate] Scan started — run #${run.id}. Polling for completion...`);

  const deadline = Date.now() + args.timeoutSeconds * 1000;
  try {
    while (Date.now() < deadline && run.status === 'RUNNING') {
      await sleep(args.pollIntervalSeconds * 1000);
      run = await pollRun(args, run.id);
      if (run.status === 'RUNNING') {
        console.log(`[a11y-gate] Still running... (${Math.max(0, Math.round((deadline - Date.now()) / 1000))}s left)`);
      }
    }
  } catch (e) {
    console.error(`[a11y-gate] Error while polling: ${e.message}`);
    process.exit(2);
  }

  if (run.status === 'RUNNING') {
    console.error(`[a11y-gate] Timed out after ${args.timeoutSeconds}s waiting for run #${run.id} to finish.`);
    process.exit(1);
  }

  if (run.status === 'FAILED') {
    console.error(`[a11y-gate] Scan run #${run.id} failed: ${run.errorMessage || '(no error message)'}`);
    process.exit(1);
  }

  const {
    totalViolations = 0,
    criticalCount = 0,
    seriousCount = 0,
    moderateCount = 0,
    minorCount = 0,
    needsReviewCount = 0,
    passedCount = 0,
  } = run;

  console.log('');
  console.log(`[a11y-gate] Scan completed — run #${run.id} (${run.status})`);
  console.log(`  Total violations : ${totalViolations}`);
  console.log(`  Critical         : ${criticalCount}`);
  console.log(`  Serious          : ${seriousCount}`);
  console.log(`  Moderate         : ${moderateCount}`);
  console.log(`  Minor            : ${minorCount}`);
  console.log(`  Needs review     : ${needsReviewCount}`);
  console.log(`  Passed checks    : ${passedCount}`);
  console.log('');

  const breaches = [];
  if (criticalCount > args.maxCritical) breaches.push(`critical ${criticalCount} > allowed ${args.maxCritical}`);
  if (seriousCount > args.maxSerious) breaches.push(`serious ${seriousCount} > allowed ${args.maxSerious}`);

  if (breaches.length > 0) {
    console.error(`[a11y-gate] FAILED — threshold breached: ${breaches.join('; ')}`);
    process.exit(1);
  }

  console.log('[a11y-gate] PASSED — within configured thresholds.');
  process.exit(0);
}

main();
