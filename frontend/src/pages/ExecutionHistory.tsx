import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Search, History } from 'lucide-react';
import { dashboardApi } from '../services/uiAutomationApi';
import type { StandardRunSummary, DataDrivenRunSummary, AccessibilityRunSummary, ApiRunSummary } from '../types';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';

// One global, application-wide execution log spanning every testing
// capability — UI Automation, Data Driven, and Accessibility all persist
// their own run/scan-run rows independently; this page is the only place
// they're merged into a single sorted-by-date list, each row linking to its
// own real report page.
type RunKind = 'standard' | 'data-driven' | 'accessibility' | 'api-testing';

type UnifiedRun = {
  id: number;
  kind: RunKind;
  testId: number | null;
  testName: string;
  status: string;
  totalDurationMs: number;
  startedAt: string;
  resultLabel: string;
};

type KindFilter = 'all' | RunKind;
type StatusFilter = 'all' | 'PASSED' | 'COMPLETED' | 'FAILED' | 'RUNNING';
type SortMode = 'newest' | 'oldest' | 'duration-desc';

const KIND_LABEL: Record<RunKind, string> = {
  standard: 'UI Automation',
  'data-driven': 'Data Driven',
  accessibility: 'Accessibility',
  'api-testing': 'API Testing',
};

const KIND_BADGE_CLASS: Record<RunKind, string> = {
  standard: 'badge-info',
  'data-driven': 'badge-severity-review',
  accessibility: 'badge-severity-moderate',
  'api-testing': 'badge-severity-passed',
};

function reportPathFor(run: UnifiedRun): string {
  if (run.kind === 'data-driven') return `/ui-automation/data-driven/runs/${run.id}`;
  if (run.kind === 'accessibility') return `/accessibility/runs/${run.id}`;
  if (run.kind === 'api-testing') return `/api-testing/runs/${run.id}`;
  return `/ui-automation/runs/${run.id}`;
}

function toUnifiedRuns(
  standard: StandardRunSummary[],
  dataDriven: DataDrivenRunSummary[],
  accessibility: AccessibilityRunSummary[],
  apiRuns: ApiRunSummary[]
): UnifiedRun[] {
  return [
    ...standard.map(r => ({
      id: r.id,
      kind: 'standard' as const,
      testId: r.testId,
      testName: r.testName,
      status: r.status,
      totalDurationMs: r.totalDurationMs,
      startedAt: r.startedAt,
      resultLabel: `${r.passedSteps} / ${r.totalSteps} steps`,
    })),
    ...dataDriven.map(r => ({
      id: r.id,
      kind: 'data-driven' as const,
      testId: r.testId,
      testName: r.testName,
      status: r.status,
      totalDurationMs: r.totalDurationMs,
      startedAt: r.startedAt,
      resultLabel: `${r.passedRows} / ${r.totalRows} rows`,
    })),
    ...accessibility.map(r => ({
      id: r.id,
      kind: 'accessibility' as const,
      testId: r.scanId,
      testName: r.scanName,
      status: r.status,
      totalDurationMs: r.durationMs,
      startedAt: r.startedAt,
      resultLabel: r.status === 'COMPLETED' ? `${r.totalViolations} violation${r.totalViolations === 1 ? '' : 's'}` : '—',
    })),
    ...apiRuns.map(r => ({
      id: r.id,
      kind: 'api-testing' as const,
      testId: r.collectionId,
      testName: r.runName,
      status: r.status,
      totalDurationMs: r.totalDurationMs,
      startedAt: r.startedAt,
      resultLabel: `${r.passedRequests} / ${r.totalRequests} requests`,
    })),
  ].sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime());
}

export default function ExecutionHistory() {
  const [searchParams] = useSearchParams();
  const [unifiedRuns, setUnifiedRuns] = useState<UnifiedRun[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  const [search, setSearch] = useState('');
  // Pre-set from a `?type=` link elsewhere in the app (e.g. a capability card) —
  // still just a client-side filter over the same already-fetched data.
  const isKnownKind = (type: string | null): type is RunKind =>
    type === 'standard' || type === 'data-driven' || type === 'accessibility' || type === 'api-testing';

  const [kindFilter, setKindFilter] = useState<KindFilter>(() => {
    const type = searchParams.get('type');
    return isKnownKind(type) ? type : 'all';
  });
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sortMode, setSortMode] = useState<SortMode>('newest');

  // Re-sync if `?type=` changes while already on this page — React Router
  // keeps the same component mounted, so the lazy useState initializer above
  // wouldn't re-run on its own.
  useEffect(() => {
    const type = searchParams.get('type');
    setKindFilter(isKnownKind(type) ? type : 'all');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [searchParams]);

  useEffect(() => {
    dashboardApi.getAllRuns()
      .then(data => setUnifiedRuns(toUnifiedRuns(data.standardRuns, data.dataDrivenRuns, data.accessibilityRuns, data.apiRuns)))
      .catch(() => setError('Failed to load execution history.'))
      .finally(() => setIsLoading(false));
  }, []);

  const visibleRuns = useMemo(() => {
    let list = unifiedRuns;
    if (kindFilter !== 'all') list = list.filter(r => r.kind === kindFilter);
    if (statusFilter !== 'all') list = list.filter(r => r.status === statusFilter);
    const query = search.trim().toLowerCase();
    if (query) {
      list = list.filter(r =>
        r.testName.toLowerCase().includes(query) ||
        String(r.id).includes(query) ||
        r.resultLabel.toLowerCase().includes(query)
      );
    }
    const sorted = [...list];
    if (sortMode === 'oldest') sorted.sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime());
    else if (sortMode === 'duration-desc') sorted.sort((a, b) => b.totalDurationMs - a.totalDurationMs);
    return sorted;
  }, [unifiedRuns, kindFilter, statusFilter, search, sortMode]);

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Execution History' }]}
        title="Execution History"
        subtitle={<p style={{ margin: 0 }}>Every execution across UI Automation, Data Driven, and Accessibility Testing.</p>}
      />

      {error ? (
        <div className="card">
          <EmptyState title={error} />
        </div>
      ) : isLoading ? (
        <Skeleton variant="row" count={6} />
      ) : unifiedRuns.length === 0 ? (
        <div className="card">
          <EmptyState icon={<History size={36} />} title="No executions yet" description="Run a UI Automation test, a Data Driven test, or an Accessibility scan to see history here." />
        </div>
      ) : (
        <div className="card">
          <div className="flex items-center gap-4 mb-4 flex-wrap">
            <div className="search-box" style={{ flex: '1 1 240px' }}>
              <Search size={15} />
              <input className="input" placeholder="Search by name, run #, or result..." value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            <select className="select" style={{ width: 'auto', minWidth: 160 }} value={kindFilter} onChange={e => setKindFilter(e.target.value as KindFilter)}>
              <option value="all">All types</option>
              <option value="standard">UI Automation</option>
              <option value="data-driven">Data Driven</option>
              <option value="accessibility">Accessibility</option>
              <option value="api-testing">API Testing</option>
            </select>
            <select className="select" style={{ width: 'auto', minWidth: 150 }} value={statusFilter} onChange={e => setStatusFilter(e.target.value as StatusFilter)}>
              <option value="all">All statuses</option>
              <option value="PASSED">Passed</option>
              <option value="COMPLETED">Completed</option>
              <option value="FAILED">Failed</option>
              <option value="RUNNING">Running</option>
            </select>
            <select className="select" style={{ width: 'auto', minWidth: 150 }} value={sortMode} onChange={e => setSortMode(e.target.value as SortMode)}>
              <option value="newest">Newest first</option>
              <option value="oldest">Oldest first</option>
              <option value="duration-desc">Longest duration</option>
            </select>
          </div>

          {visibleRuns.length === 0 ? (
            <EmptyState title="No runs match your filters" />
          ) : (
            <div className="table-wrapper" style={{ border: 'none' }}>
              <table>
                <thead>
                  <tr>
                    <th>Run ID</th>
                    <th>Name</th>
                    <th>Type</th>
                    <th>Status</th>
                    <th>Duration</th>
                    <th>Result</th>
                    <th>Date</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRuns.map(run => (
                    <tr key={`${run.kind}-${run.id}`}>
                      <td>#{run.id}</td>
                      <td className="truncate" style={{ maxWidth: 220 }}>
                        {run.testId != null ? (
                          <Link
                            to={
                              run.kind === 'accessibility' ? `/accessibility/runs/${run.id}`
                                : run.kind === 'api-testing' ? `/api-testing/collections/${run.testId}`
                                : `/ui-automation/tests/${run.testId}`
                            }
                            className="no-underline font-medium text-primary"
                          >
                            {run.testName}
                          </Link>
                        ) : run.testName}
                      </td>
                      <td>
                        <span className={`badge ${KIND_BADGE_CLASS[run.kind]}`} style={{ textTransform: 'none' }}>
                          {KIND_LABEL[run.kind]}
                        </span>
                      </td>
                      <td><StatusBadge status={run.status} size="sm" /></td>
                      <td>{(run.totalDurationMs / 1000).toFixed(1)}s</td>
                      <td>{run.resultLabel}</td>
                      <td className="text-muted">{new Date(run.startedAt).toLocaleString()}</td>
                      <td style={{ textAlign: 'right' }}>
                        <Link to={reportPathFor(run)} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }}>
                          View Report
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
