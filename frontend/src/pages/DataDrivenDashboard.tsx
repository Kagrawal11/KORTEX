import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Plus, RefreshCw, Search, Database, FileSpreadsheet, PlayCircle, FileText, ListChecks,
} from 'lucide-react';
import { dashboardApi, uiAutomationApi } from '../services/uiAutomationApi';
import type { DataDrivenRunSummary, TestScenario } from '../types';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';

type StatusFilter = 'all' | 'PASSED' | 'FAILED' | 'RUNNING';
type SortMode = 'newest' | 'oldest' | 'most-rows';

interface TestSummaryRow {
  testId: number;
  testName: string;
  targetUrl: string;
  lastRun: DataDrivenRunSummary;
  totalRunsForTest: number;
}

export default function DataDrivenDashboard() {
  const navigate = useNavigate();
  const [runs, setRuns] = useState<DataDrivenRunSummary[]>([]);
  const [tests, setTests] = useState<TestScenario[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sortMode, setSortMode] = useState<SortMode>('newest');

  const fetchAll = async () => {
    try {
      setIsLoading(true);
      setError('');
      const [runsData, testsData] = await Promise.all([
        dashboardApi.getAllRuns(),
        uiAutomationApi.getTests(),
      ]);
      setRuns(runsData.dataDrivenRuns);
      setTests(testsData);
    } catch {
      setError('Failed to load Data Driven data.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, []);

  const targetUrlByTestId = useMemo(() => {
    const map = new Map<number, string>();
    tests.forEach(t => map.set(t.id, t.targetUrl));
    return map;
  }, [tests]);

  const recordedTestCount = useMemo(() => tests.filter(t => t.steps && t.steps.length > 0).length, [tests]);

  // One row per distinct test that has REAL data-driven run history — a test
  // with zero data-driven runs simply doesn't appear here yet (no fabricated
  // "0 runs" placeholder rows).
  const testRows: TestSummaryRow[] = useMemo(() => {
    const byTest = new Map<number, DataDrivenRunSummary[]>();
    for (const r of runs) {
      if (r.testId == null) continue;
      const list = byTest.get(r.testId) ?? [];
      list.push(r);
      byTest.set(r.testId, list);
    }
    const rows: TestSummaryRow[] = [];
    byTest.forEach((testRuns, testId) => {
      const sorted = [...testRuns].sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime());
      rows.push({
        testId,
        testName: sorted[0].testName,
        targetUrl: targetUrlByTestId.get(testId) ?? '',
        lastRun: sorted[0],
        totalRunsForTest: sorted.length,
      });
    });
    return rows;
  }, [runs, targetUrlByTestId]);

  const visibleRows = useMemo(() => {
    let list = testRows;
    if (statusFilter !== 'all') list = list.filter(r => r.lastRun.status === statusFilter);
    const query = search.trim().toLowerCase();
    if (query) list = list.filter(r => r.testName.toLowerCase().includes(query) || r.targetUrl.toLowerCase().includes(query));
    const sorted = [...list];
    if (sortMode === 'oldest') sorted.sort((a, b) => new Date(a.lastRun.startedAt).getTime() - new Date(b.lastRun.startedAt).getTime());
    else if (sortMode === 'most-rows') sorted.sort((a, b) => b.lastRun.totalRows - a.lastRun.totalRows);
    else sorted.sort((a, b) => new Date(b.lastRun.startedAt).getTime() - new Date(a.lastRun.startedAt).getTime());
    return sorted;
  }, [testRows, search, statusFilter, sortMode]);

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Data Driven' }]}
        title="Data Driven Testing"
        subtitle={<p style={{ margin: 0 }}>Run the same automated test with multiple sets of test data.</p>}
        actions={
          <>
            <button className="btn btn-secondary" onClick={fetchAll} disabled={isLoading}>
              <RefreshCw size={18} className={isLoading ? 'animate-spin' : ''} /> Refresh
            </button>
            <button className="btn btn-primary" onClick={() => navigate('/data-driven/new')}>
              <Plus size={18} /> Create Data Driven Test
            </button>
          </>
        }
      />

      {error ? (
        <div className="card">
          <EmptyState title="Unable to load Data Driven data" description={error} action={<button className="btn btn-primary" onClick={fetchAll}>Retry</button>} />
        </div>
      ) : isLoading ? (
        <>
          <div className="mb-6"><Skeleton variant="card" count={4} /></div>
          <Skeleton variant="row" count={4} />
        </>
      ) : testRows.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<Database size={40} />}
            title="No Data Driven tests yet"
            description="Create a Data Driven test to run a recorded UI Automation test against multiple rows of Excel/CSV data — one execution per row, with per-row pass/fail results."
            action={<button className="btn btn-primary mx-auto" onClick={() => navigate('/data-driven/new')}><Plus size={18} /> Create Data Driven Test</button>}
          />
        </div>
      ) : (
        <>
          <div className="flex gap-4 mb-8 flex-wrap">
            <StatCard icon={<Database size={16} />} label="Data Driven Tests" value={testRows.length} flex="1 1 220px" />
            <StatCard icon={<PlayCircle size={16} />} label="Total Runs" value={runs.length} tone="primary" flex="1 1 220px" />
            <StatCard icon={<ListChecks size={16} />} label="Recorded Tests Available" value={recordedTestCount} flex="1 1 220px" />
          </div>

          <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Data Driven Tests</h3>

          <div className="flex items-center gap-4 mb-4 flex-wrap">
            <div className="search-box" style={{ flex: '1 1 260px' }}>
              <Search size={16} />
              <input className="input" placeholder="Search by test name or URL…" value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            <select className="select" style={{ width: 'auto', minWidth: 150 }} value={statusFilter} onChange={e => setStatusFilter(e.target.value as StatusFilter)}>
              <option value="all">All statuses</option>
              <option value="PASSED">Passed</option>
              <option value="FAILED">Failed</option>
              <option value="RUNNING">Running</option>
            </select>
            <select className="select" style={{ width: 'auto', minWidth: 170 }} value={sortMode} onChange={e => setSortMode(e.target.value as SortMode)}>
              <option value="newest">Newest first</option>
              <option value="oldest">Oldest first</option>
              <option value="most-rows">Most data rows</option>
            </select>
          </div>

          {visibleRows.length === 0 ? (
            <div className="card"><EmptyState title="No tests match your filters" /></div>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Test Name</th>
                    <th>Target URL</th>
                    <th>Data Source</th>
                    <th>Rows</th>
                    <th>Last Run</th>
                    <th>Duration</th>
                    <th>Runs</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleRows.map(row => (
                    <tr key={row.testId}>
                      <td>
                        <Link to={`/ui-automation/tests/${row.testId}?tab=data-driven`} className="no-underline font-medium text-primary">
                          {row.testName}
                        </Link>
                      </td>
                      <td className="text-muted truncate" style={{ maxWidth: 200 }}>{row.targetUrl || '—'}</td>
                      <td className="text-muted" style={{ maxWidth: 180 }}>
                        <div className="flex items-center gap-2">
                          <FileSpreadsheet size={14} style={{ flexShrink: 0 }} />
                          <span className="truncate">{row.lastRun.datasetFilename || '—'}</span>
                        </div>
                      </td>
                      <td>{row.lastRun.totalRows}</td>
                      <td><StatusBadge status={row.lastRun.status} size="sm" /></td>
                      <td>{(row.lastRun.totalDurationMs / 1000).toFixed(1)}s</td>
                      <td className="text-muted">{row.totalRunsForTest}</td>
                      <td style={{ textAlign: 'right' }}>
                        <div className="flex justify-end gap-2">
                          <Link to={`/ui-automation/tests/${row.testId}?tab=data-driven`} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }} title="Open test">
                            <Database size={15} />
                          </Link>
                          <Link to={`/ui-automation/data-driven/runs/${row.lastRun.id}`} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }} title="View last report">
                            <FileText size={15} />
                          </Link>
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
