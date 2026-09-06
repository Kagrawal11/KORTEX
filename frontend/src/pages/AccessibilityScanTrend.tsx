import { useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { TrendingUp, TrendingDown, Minus, FileText } from 'lucide-react';
import { accessibilityApi } from '../services/accessibilityApi';
import type { AccessibilityScanTrend as AccessibilityScanTrendData } from '../types';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';

function verdictBadge(regression: boolean, improvement: boolean) {
  if (regression) return <span className="badge badge-error" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><TrendingUp size={12} /> Regression</span>;
  if (improvement) return <span className="badge badge-success" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><TrendingDown size={12} /> Improved</span>;
  return <span className="badge badge-neutral" style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}><Minus size={12} /> No change</span>;
}

function delta(value: number | null) {
  if (value === null) return <span className="text-subtle">—</span>;
  if (value === 0) return <span className="text-muted">±0</span>;
  const positive = value > 0;
  return <span style={{ color: positive ? 'var(--error)' : 'var(--success)' }}>{positive ? `+${value}` : value}</span>;
}

export default function AccessibilityScanTrend() {
  const { scanId } = useParams<{ scanId: string }>();
  const [trend, setTrend] = useState<AccessibilityScanTrendData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!scanId) return;
    (async () => {
      try {
        setIsLoading(true);
        setError('');
        setTrend(await accessibilityApi.getScanTrend(Number(scanId)));
      } catch {
        setError('Failed to load scan trend history.');
      } finally {
        setIsLoading(false);
      }
    })();
  }, [scanId]);

  const completedPoints = useMemo(() => (trend?.points ?? []).filter(p => p.status === 'COMPLETED'), [trend]);
  const maxViolations = useMemo(() => Math.max(1, ...completedPoints.map(p => p.totalViolations)), [completedPoints]);
  const latestRegression = completedPoints.length > 0 && completedPoints[completedPoints.length - 1].regression;

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[
          { label: 'Dashboard', to: '/' },
          { label: 'Accessibility', to: '/accessibility' },
          { label: trend?.scanName ?? 'Trend' },
        ]}
        title={trend ? `${trend.scanName} — Run History` : 'Scan Trend'}
        subtitle={trend ? <p style={{ margin: 0 }}>{trend.targetUrl}</p> : undefined}
      />

      {error ? (
        <div className="card">
          <EmptyState title="Unable to load trend" description={error} />
        </div>
      ) : isLoading ? (
        <>
          <div className="mb-6"><Skeleton variant="card" count={1} /></div>
          <Skeleton variant="row" count={4} />
        </>
      ) : !trend || trend.points.length === 0 ? (
        <div className="card">
          <EmptyState title="No runs yet" description="Re-run this scan at least once to start building a trend." />
        </div>
      ) : (
        <>
          {completedPoints.length >= 2 && (
            <div className={`card mb-6${latestRegression ? '' : ''}`} style={latestRegression ? { backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)' } : undefined}>
              <div className="flex items-center gap-2 mb-4">
                <TrendingUp size={16} />
                <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Total Violations Over Time</h3>
              </div>
              <div className="trend-chart">
                <div className="trend-bars" style={{ height: 140, flex: '0 0 140px' }}>
                  {completedPoints.map(p => (
                    <div key={p.runId} className="trend-bar-track" title={`Run #${p.runId} · ${new Date(p.startedAt).toLocaleDateString()} · ${p.totalViolations} violation${p.totalViolations === 1 ? '' : 's'}`}>
                      <div
                        className={`trend-bar${p.totalViolations > 0 ? ' has-value' : ''}`}
                        style={{
                          height: `${Math.max(6, (p.totalViolations / maxViolations) * 100)}%`,
                          ...(p.regression ? { background: 'linear-gradient(180deg, var(--error) 0%, var(--error) 100%)' } : {}),
                        }}
                      />
                    </div>
                  ))}
                </div>
                <div className="trend-labels">
                  {completedPoints.map(p => (
                    <span key={p.runId} className="text-xs text-subtle">{new Date(p.startedAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</span>
                  ))}
                </div>
              </div>
              {latestRegression && (
                <div className="text-xs mt-4" style={{ color: 'var(--error)' }}>
                  The most recent run introduced new critical or serious violations compared to the run before it.
                </div>
              )}
            </div>
          )}

          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Status</th>
                  <th>Duration</th>
                  <th>Critical</th>
                  <th>Serious</th>
                  <th>Moderate</th>
                  <th>Minor</th>
                  <th>Total</th>
                  <th>Δ Critical</th>
                  <th>Δ Total</th>
                  <th>Verdict</th>
                  <th style={{ textAlign: 'right' }}>Report</th>
                </tr>
              </thead>
              <tbody>
                {[...trend.points].reverse().map(p => (
                  <tr key={p.runId}>
                    <td className="text-muted">{new Date(p.startedAt).toLocaleString()}</td>
                    <td><StatusBadge status={p.status} size="sm" /></td>
                    <td>{(p.durationMs / 1000).toFixed(1)}s</td>
                    <td>{p.status === 'COMPLETED' ? p.criticalCount : '—'}</td>
                    <td>{p.status === 'COMPLETED' ? p.seriousCount : '—'}</td>
                    <td>{p.status === 'COMPLETED' ? p.moderateCount : '—'}</td>
                    <td>{p.status === 'COMPLETED' ? p.minorCount : '—'}</td>
                    <td>{p.status === 'COMPLETED' ? p.totalViolations : '—'}</td>
                    <td>{p.status === 'COMPLETED' ? delta(p.criticalDelta) : '—'}</td>
                    <td>{p.status === 'COMPLETED' ? delta(p.totalViolationsDelta) : '—'}</td>
                    <td>{p.status === 'COMPLETED' && p.criticalDelta !== null ? verdictBadge(p.regression, p.improvement) : <span className="text-subtle">—</span>}</td>
                    <td style={{ textAlign: 'right' }}>
                      <Link to={`/accessibility/runs/${p.runId}`} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }} title="View report">
                        <FileText size={15} />
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  );
}
