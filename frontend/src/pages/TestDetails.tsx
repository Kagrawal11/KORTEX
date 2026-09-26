import { useEffect, useMemo, useState } from 'react';
import { useParams, Link, useNavigate, useSearchParams } from 'react-router-dom';
import { uiAutomationApi, dataDrivenApi } from '../services/uiAutomationApi';
import type { TestScenario, TestRun, DataDrivenRun } from '../types';
import { Play, Clock, MonitorPlay, Activity, Download, Copy, Check, Code, Database, Search, ListChecks, History } from 'lucide-react';
import DataDrivenPanel from '../components/DataDrivenPanel';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';

// A single row in the unified Execution History tab — standard runs and
// data-driven runs were previously shown in two entirely separate places
// (this tab only ever fetched standard TestRuns; a completed data-driven run
// had NO listing anywhere in this page at all, even though it had already
// been persisted correctly — the run simply never surfaced here). Merging
// both into one sorted-by-date list, with each row linking to its own real
// report page, is what "Execution History" already implied but didn't do.
type UnifiedRun = {
  id: number;
  kind: 'standard' | 'data-driven';
  status: string;
  totalDurationMs: number;
  startedAt: string;
  resultLabel: string;
};

type KindFilter = 'all' | 'standard' | 'data-driven';
type StatusFilter = 'all' | 'PASSED' | 'FAILED' | 'RUNNING';
type SortMode = 'newest' | 'oldest' | 'duration-desc';

export default function TestDetails() {
  const { testId } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const [test, setTest] = useState<TestScenario | null>(null);
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [ddRuns, setDdRuns] = useState<DataDrivenRun[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isStartingRun, setIsStartingRun] = useState(false);
  const [error, setError] = useState('');
  // `?tab=` lets other pages (e.g. the Data Driven workspace) deep-link
  // straight into a specific tab instead of always landing on Recorded Steps.
  const [activeTab, setActiveTab] = useState<'steps' | 'runs' | 'script' | 'data-driven'>(() => {
    const tab = searchParams.get('tab');
    return tab === 'runs' || tab === 'script' || tab === 'data-driven' ? tab : 'steps';
  });
  const [scriptContent, setScriptContent] = useState<string | null>(null);
  const [isLoadingScript, setIsLoadingScript] = useState(false);
  const [copied, setCopied] = useState(false);

  const [historySearch, setHistorySearch] = useState('');
  const [kindFilter, setKindFilter] = useState<KindFilter>('all');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sortMode, setSortMode] = useState<SortMode>('newest');

  useEffect(() => {
    if (testId) {
      loadData();
    }
  }, [testId]);

  // Re-sync the active tab if `?tab=` changes while this exact component
  // instance stays mounted (navigating between two /tests/:id URLs reuses
  // it — the lazy useState initializer above only runs on first mount).
  useEffect(() => {
    const tab = searchParams.get('tab');
    if (tab === 'runs' || tab === 'script' || tab === 'data-driven' || tab === 'steps') {
      setActiveTab(tab);
    }
  }, [searchParams]);

  const loadData = async () => {
    try {
      setIsLoading(true);
      setError('');

      // Load test details (required)
      const testData = await uiAutomationApi.getTest(Number(testId));
      setTest(testData);

      // Load both run types separately — non-fatal if either fails, and
      // independently of each other (a data-driven fetch failure shouldn't
      // blank out standard runs that loaded fine, or vice versa).
      try {
        const runsData = await uiAutomationApi.getTestRuns(Number(testId));
        setRuns(runsData);
      } catch {
        setRuns([]); // runs failed, but still show the test details
      }
      try {
        const ddRunsData = await dataDrivenApi.getRunsForScenario(Number(testId));
        setDdRuns(ddRunsData);
      } catch {
        setDdRuns([]);
      }
    } catch (err: any) {
      const msg = err?.response?.data?.message
        || err?.response?.statusText
        || err?.message
        || 'Failed to load test details.';
      setError(`Error: ${msg}`);
    } finally {
      setIsLoading(false);
    }
  };

  const handleRunTest = async () => {
    try {
      setIsStartingRun(true);
      setError('');
      // Backend returns immediately with a RUNNING run ID
      // Playwright executes in background on the server
      const run = await uiAutomationApi.runTest(Number(testId));
      // Navigate to the report page immediately — it will show RUNNING → PASSED/FAILED
      navigate(`/ui-automation/runs/${run.id}`);
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || 'Failed to start test run.';
      setError(`Error: ${msg}`);
      setIsStartingRun(false);
    }
  };

  const handleExportScript = async () => {
    if (scriptContent) {
      setActiveTab('script');
      return;
    }
    try {
      setIsLoadingScript(true);
      setActiveTab('script');
      const resp = await fetch(`/api/ui-automation/tests/${testId}/export`);
      const text = await resp.text();
      setScriptContent(text);
    } catch {
      setScriptContent('// Error: Could not load script from backend.');
    } finally {
      setIsLoadingScript(false);
    }
  };

  const handleDownloadScript = () => {
    if (!scriptContent || !test) return;
    const blob = new Blob([scriptContent], { type: 'text/plain' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `${test.name.replace(/[^A-Za-z0-9_-]/g, '_')}Test.java`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleCopy = () => {
    if (!scriptContent) return;
    navigator.clipboard.writeText(scriptContent).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  // This merge-and-sort must not change — see docs/BUGFIX_PLAN.md: data-driven
  // runs previously never appeared anywhere in this UI at all.
  const unifiedRuns: UnifiedRun[] = [
    ...runs.map(r => ({
      id: r.id,
      kind: 'standard' as const,
      status: r.status,
      totalDurationMs: r.totalDurationMs,
      startedAt: r.startedAt,
      resultLabel: `${r.passedSteps} / ${r.totalSteps} steps`,
    })),
    ...ddRuns.map(r => ({
      id: r.id,
      kind: 'data-driven' as const,
      status: r.status,
      totalDurationMs: r.totalDurationMs,
      startedAt: r.startedAt,
      resultLabel: `${r.passedRows} / ${r.totalRows} rows`,
    })),
  ].sort((a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime());

  // Purely a view derived from unifiedRuns — the merge itself above is untouched.
  const visibleRuns = useMemo(() => {
    let list = unifiedRuns;
    if (kindFilter !== 'all') list = list.filter(r => r.kind === kindFilter);
    if (statusFilter !== 'all') list = list.filter(r => r.status === statusFilter);
    const query = historySearch.trim().toLowerCase();
    if (query) list = list.filter(r => String(r.id).includes(query) || r.resultLabel.toLowerCase().includes(query));

    const sorted = [...list];
    if (sortMode === 'oldest') sorted.sort((a, b) => new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime());
    else if (sortMode === 'duration-desc') sorted.sort((a, b) => b.totalDurationMs - a.totalDurationMs);
    // 'newest' is already the incoming order from unifiedRuns
    return sorted;
  }, [unifiedRuns, kindFilter, statusFilter, historySearch, sortMode]);

  if (isLoading) {
    return (
      <div className="container">
        <Skeleton variant="text" width="40%" height={32} />
        <div className="mt-6"><Skeleton variant="card" count={1} height={140} /></div>
      </div>
    );
  }

  if (error || !test) {
    return (
      <div className="container">
        <div className="card">
          <EmptyState
            title={error || 'Test not found'}
            action={<Link to="/ui-automation" className="btn btn-secondary no-underline">Back to Dashboard</Link>}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'UI Automation', to: '/ui-automation' }, { label: test.name }]}
        title={test.name}
        subtitle={
          <div className="flex gap-4 items-center flex-wrap text-muted text-sm">
            <span className="flex items-center gap-1"><MonitorPlay size={15} /> {test.targetUrl}</span>
            <span className="flex items-center gap-1"><Clock size={15} /> Created {new Date(test.createdAt).toLocaleDateString()}</span>
            <span className="flex items-center gap-1"><Activity size={15} /> {test.steps?.length || 0} Steps</span>
          </div>
        }
        actions={
          <>
            <Link to={`/ui-automation/recording/${test.id}`} className="btn btn-secondary no-underline">
              Re-record
            </Link>
            <button
              className="btn btn-secondary"
              onClick={handleExportScript}
              disabled={!test.steps || test.steps.length === 0}
              title="View & export as Playwright Java script"
            >
              <Code size={18} /> View Script
            </button>
            <button className="btn btn-primary" onClick={handleRunTest} disabled={isStartingRun || !test.steps || test.steps.length === 0}>
              {isStartingRun ? 'Starting Run...' : <><Play size={18} /> Run Test</>}
            </button>
          </>
        }
      />

      {(!test.steps || test.steps.length === 0) && (
        <div className="card mb-8" style={{ backgroundColor: 'var(--warning-bg)', borderColor: 'var(--warning)', color: 'var(--warning)' }}>
          <strong>No steps recorded.</strong> Click "Re-record" to launch the browser and capture actions.
        </div>
      )}

      <div className="tabs-bar mb-6">
        <button className={`tab-btn ${activeTab === 'steps' ? 'active' : ''}`} onClick={() => setActiveTab('steps')}>
          <ListChecks size={14} /> Recorded Steps
        </button>
        <button className={`tab-btn ${activeTab === 'runs' ? 'active' : ''}`} onClick={() => setActiveTab('runs')}>
          <History size={14} /> Execution History
          {unifiedRuns.length > 0 && <span className="badge-count">{unifiedRuns.length}</span>}
        </button>
        <button className={`tab-btn ${activeTab === 'script' ? 'active' : ''}`} onClick={handleExportScript}>
          <Code size={14} /> Test Script
        </button>
        <button className={`tab-btn ${activeTab === 'data-driven' ? 'active' : ''}`} onClick={() => setActiveTab('data-driven')}>
          <Database size={14} /> Data-Driven
        </button>
      </div>

      {/* Tab Content */}
      {activeTab === 'steps' && (
        <div className="card">
          {(!test.steps || test.steps.length === 0) ? (
            <EmptyState icon={<ListChecks size={36} />} title="No steps recorded yet" />
          ) : (
            <div className="table-wrapper" style={{ border: 'none' }}>
              <table>
                <thead>
                  <tr>
                    <th style={{ width: '60px', textAlign: 'center' }}>#</th>
                    <th style={{ width: '150px' }}>Action</th>
                    <th>Target / Selector</th>
                    <th>Input Value</th>
                  </tr>
                </thead>
                <tbody>
                  {test.steps.map((step, index) => (
                    <tr key={step.id || index}>
                      <td style={{ textAlign: 'center' }} className="text-muted">{step.stepOrder}</td>
                      <td><span className="badge badge-neutral">{step.actionType}</span></td>
                      <td className="font-mono text-sm text-muted">{step.primarySelector}</td>
                      <td className="font-mono text-sm">{step.inputValue || '-'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}

      {activeTab === 'runs' && (
        <div className="card">
          {unifiedRuns.length === 0 ? (
            <EmptyState icon={<ListChecks size={36} />} title="No test runs yet" description="Run this test to see execution history here." />
          ) : (
            <>
              <div className="flex items-center gap-4 mb-4 flex-wrap">
                <div className="search-box" style={{ flex: '1 1 220px' }}>
                  <Search size={15} />
                  <input className="input" placeholder="Search by run # or result…" value={historySearch} onChange={e => setHistorySearch(e.target.value)} />
                </div>
                <select className="select" style={{ width: 'auto', minWidth: 140 }} value={kindFilter} onChange={e => setKindFilter(e.target.value as KindFilter)}>
                  <option value="all">All types</option>
                  <option value="standard">Standard</option>
                  <option value="data-driven">Data-Driven</option>
                </select>
                <select className="select" style={{ width: 'auto', minWidth: 140 }} value={statusFilter} onChange={e => setStatusFilter(e.target.value as StatusFilter)}>
                  <option value="all">All statuses</option>
                  <option value="PASSED">Passed</option>
                  <option value="FAILED">Failed</option>
                  <option value="RUNNING">Running</option>
                </select>
                <select className="select" style={{ width: 'auto', minWidth: 140 }} value={sortMode} onChange={e => setSortMode(e.target.value as SortMode)}>
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
                          <td>
                            <span className="badge badge-neutral">
                              {run.kind === 'data-driven' ? 'Data-Driven' : 'Standard'}
                            </span>
                          </td>
                          <td><StatusBadge status={run.status} size="sm" /></td>
                          <td>{(run.totalDurationMs / 1000).toFixed(1)}s</td>
                          <td>{run.resultLabel}</td>
                          <td className="text-muted">{new Date(run.startedAt).toLocaleString()}</td>
                          <td style={{ textAlign: 'right' }}>
                            <Link
                              to={run.kind === 'data-driven' ? `/ui-automation/data-driven/runs/${run.id}` : `/ui-automation/runs/${run.id}`}
                              className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }}
                            >
                              View Report
                            </Link>
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
      )}

      {activeTab === 'script' && (
        <div className="card" style={{ padding: '1.5rem' }}>
          <div className="flex items-center justify-between mb-4">
            <h3 style={{ margin: 0, fontSize: '1rem' }}>Generated Playwright Java Script</h3>
            <div className="flex gap-2">
              <button
                className="btn btn-secondary"
                onClick={handleCopy}
                style={{ padding: '0.35rem 0.75rem' }}
              >
                {copied ? <><Check size={15} /> Copied!</> : <><Copy size={15} /> Copy</>}
              </button>
              <button
                className="btn btn-primary"
                onClick={handleDownloadScript}
                style={{ padding: '0.35rem 0.75rem' }}
              >
                <Download size={15} /> Download .java
              </button>
            </div>
          </div>

          {isLoadingScript ? (
            <Skeleton variant="row" count={8} />
          ) : (
            <pre
              className="font-mono"
              style={{
                background: 'var(--bg-input)',
                color: '#e2e8f0',
                padding: '1.25rem',
                borderRadius: '8px',
                fontSize: '0.8rem',
                lineHeight: '1.6',
                overflowX: 'auto',
                whiteSpace: 'pre',
                maxHeight: '60vh',
                overflowY: 'auto',
                margin: 0,
                border: '1px solid var(--border-color)',
              }}
            >
              {scriptContent}
            </pre>
          )}
        </div>
      )}

      {activeTab === 'data-driven' && (
        <div>
          <DataDrivenPanel test={test} />
        </div>
      )}

    </div>
  );
}
