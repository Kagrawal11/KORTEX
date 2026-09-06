import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Plus, RefreshCw, Search, ScanSearch, ListChecks, AlertOctagon,
  HelpCircle, CheckCircle2, FileText, RotateCw, Accessibility as AccessibilityIcon,
  TrendingUp,
} from 'lucide-react';
import { accessibilityApi } from '../services/accessibilityApi';
import type { AccessibilityScanRun, AccessibilityDashboardSummary } from '../types';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

type StatusFilter = 'all' | 'RUNNING' | 'COMPLETED' | 'FAILED';
type SortMode = 'newest' | 'oldest' | 'most-violations';

export default function AccessibilityDashboard() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [summary, setSummary] = useState<AccessibilityDashboardSummary | null>(null);
  const [runs, setRuns] = useState<AccessibilityScanRun[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [rerunningId, setRerunningId] = useState<number | null>(null);

  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sortMode, setSortMode] = useState<SortMode>('newest');

  const fetchAll = async () => {
    try {
      setIsLoading(true);
      setError('');
      const [summaryData, runsData] = await Promise.all([
        accessibilityApi.getDashboardSummary(),
        accessibilityApi.getAllRuns(),
      ]);
      setSummary(summaryData);
      setRuns(runsData);
    } catch {
      setError('Failed to load accessibility data.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, []);

  const visibleRuns = useMemo(() => {
    let list = runs;
    if (statusFilter !== 'all') list = list.filter(r => r.status === statusFilter);
    const query = search.trim().toLowerCase();
    if (query) {
      list = list.filter(r =>
        r.scan?.name?.toLowerCase().includes(query) || r.targetUrl.toLowerCase().includes(query));
    }
    const sorted = [...list];
    if (sortMode === 'oldest') sorted.sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime());
    else if (sortMode === 'most-violations') sorted.sort((a, b) => b.totalViolations - a.totalViolations);
    else sorted.sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime());
    return sorted;
  }, [runs, search, statusFilter, sortMode]);

  const handleRerun = async (scanId: number, name: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    try {
      setRerunningId(scanId);
      const run = await accessibilityApi.rerunScan(scanId);
      showToast(`Re-running "${name}"…`, 'success');
      navigate(`/accessibility/runs/${run.id}`);
    } catch (err: any) {
      showToast(err?.response?.data?.error || `Failed to re-run "${name}".`, 'error');
      setRerunningId(null);
    }
  };

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Accessibility' }]}
        title="Accessibility Testing"
        subtitle={<p style={{ margin: 0 }}>Scan web applications for accessibility issues using automated WCAG checks.</p>}
        actions={
          <>
            <button className="btn btn-secondary" onClick={fetchAll} disabled={isLoading}>
              <RefreshCw size={18} className={isLoading ? 'animate-spin' : ''} /> Refresh
            </button>
            <button className="btn btn-primary" onClick={() => navigate('/accessibility/new')}>
              <Plus size={18} /> New Accessibility Scan
            </button>
          </>
        }
      />

      {error ? (
        <div className="card">
          <EmptyState title="Unable to load accessibility data" description={error} action={<button className="btn btn-primary" onClick={fetchAll}>Retry</button>} />
        </div>
      ) : isLoading ? (
        <>
          <div className="mb-6"><Skeleton variant="card" count={4} /></div>
          <Skeleton variant="row" count={4} />
        </>
      ) : !summary || summary.totalScans === 0 ? (
        <div className="card">
          <EmptyState
            icon={<AccessibilityIcon size={40} />}
            title="No accessibility scans yet"
            description="Run your first automated WCAG scan against a real URL — results are backed by a genuine axe-core analysis, not sample data."
            action={<button className="btn btn-primary mx-auto" onClick={() => navigate('/accessibility/new')}><Plus size={18} /> New Accessibility Scan</button>}
          />
        </div>
      ) : (
        <>
          <div className="flex gap-4 mb-4 flex-wrap">
            <StatCard icon={<ListChecks size={16} />} label="Total Scans" value={summary.totalScans} />
            <StatCard icon={<ScanSearch size={16} />} label="Total Runs" value={summary.totalRuns} tone="primary" />
            {summary.latestScan && (
              <div className="card" style={{ flex: '1 1 260px' }}>
                <div className="flex items-center gap-2 mb-2 text-muted" style={{ fontSize: '0.85rem' }}>
                  <FileText size={16} /> <span>Latest Scan</span>
                </div>
                <div className="flex items-center justify-between gap-2 flex-wrap">
                  <div style={{ minWidth: 0 }}>
                    <div className="font-medium truncate">{summary.latestScan.scanName}</div>
                    <div className="text-xs text-subtle truncate">{summary.latestScan.targetUrl}</div>
                  </div>
                  <StatusBadge status={summary.latestScan.status} size="sm" />
                </div>
              </div>
            )}
          </div>

          {summary.latestScan?.status === 'COMPLETED' && (
            <div className="flex gap-4 mb-8 flex-wrap">
              <StatCard icon={<AlertOctagon size={16} />} label="Violations (latest scan)" value={summary.totalViolations} tone={summary.totalViolations > 0 ? 'error' : 'success'} />
              <StatCard icon={<AlertOctagon size={16} />} label="Critical / Serious" value={`${summary.criticalCount} / ${summary.seriousCount}`} tone={summary.criticalCount + summary.seriousCount > 0 ? 'error' : 'success'} />
              <StatCard icon={<HelpCircle size={16} />} label="Needs Review" value={summary.needsReviewCount} tone="warning" />
              <StatCard icon={<CheckCircle2 size={16} />} label="Passed Checks" value={summary.passedCount} tone="success" />
              <Link to={`/accessibility/runs/${summary.latestScan.runId}`} className="btn btn-secondary no-underline" style={{ alignSelf: 'center' }}>
                View Latest Report
              </Link>
            </div>
          )}
          {summary.latestScan?.status === 'RUNNING' && (
            <div className="card mb-8" style={{ backgroundColor: 'var(--warning-bg)', borderColor: 'var(--warning)', color: 'var(--warning)' }}>
              The latest scan is still running — <Link to={`/accessibility/runs/${summary.latestScan.runId}`} style={{ color: 'inherit', fontWeight: 600 }}>view its progress</Link>.
            </div>
          )}
          {summary.latestScan?.status === 'FAILED' && (
            <div className="card mb-8" style={{ backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)', color: 'var(--error)' }}>
              The latest scan failed — <Link to={`/accessibility/runs/${summary.latestScan.runId}`} style={{ color: 'inherit', fontWeight: 600 }}>see what went wrong</Link>.
            </div>
          )}

          <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Scan History</h3>

          <div className="flex items-center gap-4 mb-4 flex-wrap">
            <div className="search-box" style={{ flex: '1 1 260px' }}>
              <Search size={16} />
              <input className="input" placeholder="Search by scan name or URL…" value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            <select className="select" style={{ width: 'auto', minWidth: 150 }} value={statusFilter} onChange={e => setStatusFilter(e.target.value as StatusFilter)}>
              <option value="all">All statuses</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
              <option value="RUNNING">Running</option>
            </select>
            <select className="select" style={{ width: 'auto', minWidth: 170 }} value={sortMode} onChange={e => setSortMode(e.target.value as SortMode)}>
              <option value="newest">Newest first</option>
              <option value="oldest">Oldest first</option>
              <option value="most-violations">Most violations</option>
            </select>
          </div>

          {visibleRuns.length === 0 ? (
            <div className="card"><EmptyState title="No scans match your filters" /></div>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Scan Name</th>
                    <th>URL</th>
                    <th>Date</th>
                    <th>Duration</th>
                    <th>Status</th>
                    <th>Violations</th>
                    <th>Critical / Serious</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRuns.map(run => (
                    <tr key={run.id}>
                      <td>
                        <Link to={`/accessibility/runs/${run.id}`} className="no-underline font-medium text-primary">
                          {run.scan?.name ?? `Run #${run.id}`}
                        </Link>
                      </td>
                      <td className="text-muted truncate" style={{ maxWidth: 220 }}>{run.targetUrl}</td>
                      <td className="text-muted">{new Date(run.startedAt).toLocaleString()}</td>
                      <td>{(run.durationMs / 1000).toFixed(1)}s</td>
                      <td><StatusBadge status={run.status} size="sm" /></td>
                      <td>{run.status === 'COMPLETED' ? run.totalViolations : '—'}</td>
                      <td>{run.status === 'COMPLETED' ? `${run.criticalCount} / ${run.seriousCount}` : '—'}</td>
                      <td style={{ textAlign: 'right' }}>
                        <div className="flex justify-end gap-2">
                          <button
                            className="btn btn-secondary" style={{ padding: '0.25rem 0.5rem' }}
                            disabled={rerunningId === run.scan?.id}
                            title="Re-run this scan"
                            onClick={e => run.scan?.id && handleRerun(run.scan.id, run.scan.name, e)}
                          >
                            {rerunningId === run.scan?.id ? <RefreshCw size={15} className="animate-spin" /> : <RotateCw size={15} />}
                          </button>
                          <Link to={`/accessibility/runs/${run.id}`} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }} title="View report">
                            <FileText size={15} />
                          </Link>
                          {run.scan?.id && (
                            <Link to={`/accessibility/scans/${run.scan.id}/trend`} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }} title="View trend across runs">
                              <TrendingUp size={15} />
                            </Link>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </div>
  );
}
