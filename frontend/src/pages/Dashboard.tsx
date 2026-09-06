import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  MousePointerClick, Gauge, ShieldCheck, Send,
  ListChecks, PlayCircle, Database, ArrowRight, Accessibility as AccessibilityIcon,
  Layers, Activity, TrendingUp,
} from 'lucide-react';
import { dashboardApi } from '../services/uiAutomationApi';
import { accessibilityApi } from '../services/accessibilityApi';
import type { DashboardSummary, DashboardRuns, AccessibilityDashboardSummary } from '../types';
import StatCard from '../components/StatCard';
import DonutChart from '../components/DonutChart';
import Skeleton from '../components/Skeleton';
import EmptyState from '../components/EmptyState';

interface CapabilityCardProps {
  to: string;
  accent: 'blue' | 'purple' | 'cyan' | 'green';
  icon: React.ReactNode;
  title: string;
  description: string;
  cta: string;
}

// Every card shares one internal skeleton (flex column, description grows to
// fill remaining space, CTA pinned to the bottom) so all four read as equal
// first-class modules regardless of description length — never let one card
// grow taller just because its copy is longer.
const ACCENT_COLOR_VAR: Record<CapabilityCardProps['accent'], string> = {
  blue: 'var(--primary)',
  purple: 'var(--accent-purple)',
  cyan: 'var(--accent-cyan)',
  green: 'var(--success)',
};

function CapabilityCard({ to, accent, icon, title, description, cta }: CapabilityCardProps) {
  return (
    <Link
      to={to}
      className={`card card-accent-top accent-${accent} card-interactive no-underline capability-card`}
      style={{ color: 'inherit' }}
    >
      <span className={`icon-badge accent-${accent} mb-4`}>{icon}</span>
      <h3 className="mb-2">{title}</h3>
      <p className="text-sm capability-card-body">{description}</p>
      <span className="flex items-center gap-1 text-sm font-medium capability-card-cta" style={{ color: ACCENT_COLOR_VAR[accent] }}>
        {cta} <ArrowRight size={15} />
      </span>
    </Link>
  );
}

export default function Dashboard() {
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [allRuns, setAllRuns] = useState<DashboardRuns | null>(null);
  const [a11ySummary, setA11ySummary] = useState<AccessibilityDashboardSummary | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    Promise.all([
      dashboardApi.getSummary().catch(() => null),
      dashboardApi.getAllRuns().catch(() => null),
      accessibilityApi.getDashboardSummary().catch(() => null),
    ]).then(([s, r, a]) => {
      setSummary(s);
      setAllRuns(r);
      setA11ySummary(a);
      setLoadFailed(!s && !r && !a);
    }).finally(() => setIsLoading(false));
  }, []);

  const today = new Date().toLocaleDateString(undefined, { weekday: 'long', month: 'short', day: 'numeric' });

  // Derived together from `allRuns` (memoized on that single stable
  // reference) rather than three separate `?? []` fallbacks, which would
  // otherwise produce a fresh array identity every render.
  const { standardRuns, dataDrivenRuns, accessibilityRuns } = useMemo(() => ({
    standardRuns: allRuns?.standardRuns ?? [],
    dataDrivenRuns: allRuns?.dataDrivenRuns ?? [],
    accessibilityRuns: allRuns?.accessibilityRuns ?? [],
  }), [allRuns]);
  const totalExecutions = standardRuns.length + dataDrivenRuns.length + accessibilityRuns.length;

  const dataDrivenTestCount = useMemo(
    () => new Set(dataDrivenRuns.map(r => r.testId).filter((id): id is number => id != null)).size,
    [dataDrivenRuns]
  );

  const typeDistribution = useMemo(() => [
    { label: 'UI Automation', value: standardRuns.length, color: 'var(--primary)' },
    { label: 'Data Driven', value: dataDrivenRuns.length, color: 'var(--accent-purple)' },
    { label: 'Accessibility', value: accessibilityRuns.length, color: 'var(--accent-cyan)' },
  ], [standardRuns, dataDrivenRuns, accessibilityRuns]);

  const statusDistribution = useMemo(() => {
    let ok = 0, failed = 0, running = 0;
    for (const r of standardRuns) { if (r.status === 'PASSED') ok++; else if (r.status === 'FAILED') failed++; else running++; }
    for (const r of dataDrivenRuns) { if (r.status === 'PASSED') ok++; else if (r.status === 'FAILED') failed++; else running++; }
    for (const r of accessibilityRuns) { if (r.status === 'COMPLETED') ok++; else if (r.status === 'FAILED') failed++; else running++; }
    return [
      { label: 'Completed', value: ok, color: 'var(--success)' },
      { label: 'Failed', value: failed, color: 'var(--error)' },
      { label: 'Running', value: running, color: 'var(--warning)' },
    ];
  }, [standardRuns, dataDrivenRuns, accessibilityRuns]);

  // Real execution counts per day for the last 7 days, from each run's own
  // startedAt timestamp — no fabricated trend data, and simply not rendered
  // below if there's nothing in that window.
  const last7Days = useMemo(() => {
    const days: { label: string; key: string; count: number }[] = [];
    const cursor = new Date();
    cursor.setHours(0, 0, 0, 0);
    for (let i = 6; i >= 0; i--) {
      const d = new Date(cursor);
      d.setDate(d.getDate() - i);
      days.push({ label: d.toLocaleDateString(undefined, { weekday: 'short' }), key: d.toDateString(), count: 0 });
    }
    const byKey = new Map(days.map(d => [d.key, d]));
    for (const ts of [...standardRuns.map(r => r.startedAt), ...dataDrivenRuns.map(r => r.startedAt), ...accessibilityRuns.map(r => r.startedAt)]) {
      const key = new Date(ts).toDateString();
      const day = byKey.get(key);
      if (day) day.count++;
    }
    return days;
  }, [standardRuns, dataDrivenRuns, accessibilityRuns]);
  const last7DaysTotal = last7Days.reduce((sum, d) => sum + d.count, 0);
  const last7DaysMax = Math.max(1, ...last7Days.map(d => d.count));

  return (
    <div className="container">
      <div className="dashboard-hero dashboard-hero-compact mb-6">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '0.65rem', marginBottom: '0.3rem' }}>
              <img src="/kortex_logo.png" alt="Kortex" className="dashboard-logo-mark" />
              <h1 style={{ margin: 0, fontSize: 'var(--text-2xl)' }}>Welcome back, Kartik 👋</h1>
            </div>
            <p style={{ margin: 0 }}>Record, execute, and analyze automated tests across your applications.</p>
          </div>
          <div className="card" style={{ padding: '0.55rem 1.1rem', textAlign: 'center', background: 'var(--bg-elevated)' }}>
            <div className="text-xs text-muted">Today</div>
            <div className="font-medium">{today}</div>
          </div>
        </div>
      </div>

      {isLoading ? (
        <>
          <div className="mb-6"><Skeleton variant="card" count={3} /></div>
          <Skeleton variant="card" count={4} />
        </>
      ) : loadFailed ? (
        <div className="card mb-8">
          <EmptyState title="Couldn't load dashboard data" description="The backend didn't respond — the rest of the app still works normally." />
        </div>
      ) : (
        <>
          {/* PRIMARY — the three first-class testing capabilities. Pure
              discovery/navigation: no metrics here, so nothing here repeats
              what Testing Overview already states. */}
          <div className="flex gap-5 mb-8 flex-wrap">
            <CapabilityCard
              to="/ui-automation" accent="blue"
              icon={<MousePointerClick size={20} />}
              title="UI Automation"
              description="Record, manage and execute browser tests."
              cta="Go to UI Automation"
            />
            <CapabilityCard
              to="/data-driven" accent="purple"
              icon={<Database size={20} />}
              title="Data Driven Testing"
              description="Execute tests against multiple datasets."
              cta="Go to Data Driven"
            />
            <CapabilityCard
              to="/accessibility" accent="cyan"
              icon={<AccessibilityIcon size={20} />}
              title="Accessibility Testing"
              description="Scan web applications for WCAG accessibility issues."
              cta="Go to Accessibility"
            />
            <CapabilityCard
              to="/api-testing" accent="green"
              icon={<Send size={20} />}
              title="API Testing"
              description="Build, run, and chain real HTTP API test suites."
              cta="Go to API Testing"
            />
          </div>

          {/* SECONDARY — real counts, each metric appears exactly once. */}
          <div className="flex items-center justify-between mb-4">
            <h3 style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em', margin: 0 }}>Testing Overview</h3>
          </div>
          <div className="flex gap-4 mb-8 flex-wrap">
            <StatCard icon={<ListChecks size={16} />} label="UI Automation Tests" value={summary?.totalTests ?? 0} />
            <StatCard icon={<Database size={16} />} label="Data Driven Tests" value={dataDrivenTestCount} tone="primary" />
            <StatCard icon={<AccessibilityIcon size={16} />} label="Accessibility Scans" value={a11ySummary?.totalScans ?? 0} />
            <StatCard icon={<PlayCircle size={16} />} label="Total Executions" value={totalExecutions} tone="success" />
          </div>

          {/* TERTIARY — visual analytics, only ever built from real run data. */}
          {totalExecutions > 0 && (
            <>
              <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Execution Analytics</h3>
              <div className="flex gap-5 mb-8 flex-wrap">
                <div className="card analytics-card">
                  <div className="analytics-card-header">
                    <span className="analytics-icon-badge accent-blue"><Layers size={14} /></span>
                    <h3>Execution Type</h3>
                  </div>
                  <div className="analytics-card-body flex items-center gap-5">
                    <DonutChart segments={typeDistribution} size={122} strokeWidth={16} centerLabel={totalExecutions} centerSubLabel="Total" />
                    <div className="flex flex-col gap-1" style={{ flex: 1 }}>
                      {typeDistribution.map(s => (
                        <div key={s.label} className="analytics-legend-row">
                          <span className="analytics-legend-dot" style={{ background: s.color }} />
                          <span className="text-muted" style={{ flex: 1 }}>{s.label}</span>
                          <span className="font-medium" style={{ color: 'var(--text-main)' }}>{s.value}</span>
                          <span className="text-xs text-subtle" style={{ width: 34, textAlign: 'right' }}>
                            {totalExecutions > 0 ? Math.round((s.value / totalExecutions) * 100) : 0}%
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>

                <div className="card analytics-card">
                  <div className="analytics-card-header">
                    <span className="analytics-icon-badge accent-green"><Activity size={14} /></span>
                    <h3>Execution Status</h3>
                  </div>
                  <div className="analytics-card-body flex items-center gap-5">
                    <DonutChart segments={statusDistribution} size={122} strokeWidth={16} centerLabel={totalExecutions} centerSubLabel="Total" />
                    <div className="flex flex-col gap-1" style={{ flex: 1 }}>
                      {statusDistribution.map(s => (
                        <div key={s.label} className="analytics-legend-row">
                          <span className="analytics-legend-dot" style={{ background: s.color }} />
                          <span className="text-muted" style={{ flex: 1 }}>{s.label}</span>
                          <span className="font-medium" style={{ color: 'var(--text-main)' }}>{s.value}</span>
                          <span className="text-xs text-subtle" style={{ width: 34, textAlign: 'right' }}>
                            {totalExecutions > 0 ? Math.round((s.value / totalExecutions) * 100) : 0}%
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                </div>

                {last7DaysTotal > 0 && (
                  <div className="card analytics-card">
                    <div className="analytics-card-header">
                      <span className="analytics-icon-badge accent-cyan"><TrendingUp size={14} /></span>
                      <h3>Last 7 Days</h3>
                    </div>
                    <div className="analytics-card-body trend-chart">
                      <div className="trend-bars">
                        {last7Days.map(d => (
                          <div key={d.key} className="trend-bar-track" title={`${d.label}: ${d.count} execution${d.count === 1 ? '' : 's'}`}>
                            <div
                              className={`trend-bar${d.count > 0 ? ' has-value' : ''}`}
                              style={{ height: `${Math.max(6, (d.count / last7DaysMax) * 100)}%` }}
                            />
                          </div>
                        ))}
                      </div>
                      <div className="trend-labels">
                        {last7Days.map(d => (
                          <span key={d.key} className="text-xs text-subtle">{d.label[0]}</span>
                        ))}
                      </div>
                      <div className="text-xs text-muted trend-summary">
                        <strong style={{ color: 'var(--text-main)' }}>{last7DaysTotal}</strong> execution{last7DaysTotal === 1 ? '' : 's'} this week
                      </div>
                    </div>
                  </div>
                )}
              </div>
            </>
          )}
        </>
      )}

      <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>More Testing Capabilities</h3>
      <div className="flex gap-6 flex-wrap">
        {/* Performance Testing */}
        <div className="card" style={{ flex: '1 1 260px', opacity: 0.6 }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Gauge size={22} className="text-gray-400" />
              <h3 style={{ fontSize: '1rem' }}>Performance Testing</h3>
            </div>
            <span className="badge badge-neutral">Coming Soon</span>
          </div>
          <p className="mb-2 text-sm">Load test your application architecture.</p>
        </div>

        {/* Security Testing */}
        <div className="card" style={{ flex: '1 1 260px', opacity: 0.6 }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <ShieldCheck size={22} className="text-gray-400" />
              <h3 style={{ fontSize: '1rem' }}>Security Testing</h3>
            </div>
            <span className="badge badge-neutral">Coming Soon</span>
          </div>
          <p className="mb-2 text-sm">Scan for vulnerabilities and misconfigurations.</p>
        </div>
      </div>
    </div>
  );
}
