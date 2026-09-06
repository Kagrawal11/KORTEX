import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Plus, RefreshCw, FileText, Search, ListChecks, CircleDot, FileClock, Play, LayoutGrid, List, Clock } from 'lucide-react';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestScenario } from '../types';
import CreateTestDialog from '../components/CreateTestDialog';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

type SortMode = 'newest' | 'oldest' | 'name-asc' | 'name-desc';
type StatusFilter = 'all' | 'recorded' | 'draft';
type ViewMode = 'list' | 'grid';

export default function UIAutomationDashboard() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [tests, setTests] = useState<TestScenario[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');
  const [sortMode, setSortMode] = useState<SortMode>('newest');
  const [runningId, setRunningId] = useState<number | null>(null);
  const [viewMode, setViewMode] = useState<ViewMode>('list');

  const fetchTests = async () => {
    try {
      setIsLoading(true);
      setError('');
      const data = await uiAutomationApi.getTests();
      setTests(data);
    } catch (err) {
      setError('We couldn\'t retrieve your UI automation tests.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchTests();
  }, []);

  const recordedCount = tests.filter(t => t.steps && t.steps.length > 0).length;
  const draftCount = tests.length - recordedCount;
  const totalStepsRecorded = tests.reduce((sum, t) => sum + (t.steps?.length || 0), 0);

  const visibleTests = useMemo(() => {
    const query = search.trim().toLowerCase();
    let list = tests;
    if (statusFilter === 'recorded') list = list.filter(t => t.steps && t.steps.length > 0);
    else if (statusFilter === 'draft') list = list.filter(t => !t.steps || t.steps.length === 0);
    if (query) {
      list = list.filter(t =>
        t.name.toLowerCase().includes(query) || t.targetUrl.toLowerCase().includes(query)
      );
    }
    const sorted = [...list];
    switch (sortMode) {
      case 'oldest':
        sorted.sort((a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
        break;
      case 'name-asc':
        sorted.sort((a, b) => a.name.localeCompare(b.name));
        break;
      case 'name-desc':
        sorted.sort((a, b) => b.name.localeCompare(a.name));
        break;
      case 'newest':
      default:
        sorted.sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());
    }
    return sorted;
  }, [tests, search, statusFilter, sortMode]);

  const handleQuickRun = async (test: TestScenario, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    try {
      setRunningId(test.id);
      const run = await uiAutomationApi.runTest(test.id);
      showToast(`"${test.name}" started — opening the live report.`, 'success');
      navigate(`/ui-automation/runs/${run.id}`);
    } catch (err: any) {
      showToast(err?.response?.data?.message || `Failed to start "${test.name}".`, 'error');
      setRunningId(null);
    }
  };

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'UI Automation' }]}
        title="UI Automation Testing"
        subtitle={<p style={{ margin: 0 }}>Create, record, execute, and monitor browser automation tests.</p>}
        actions={
          <>
            <button className="btn btn-secondary" onClick={fetchTests} disabled={isLoading}>
              <RefreshCw size={18} className={isLoading ? 'animate-spin' : ''} />
              Refresh
            </button>
            <button className="btn btn-primary" onClick={() => setIsCreateOpen(true)}>
              <Plus size={18} />
              Create Test
            </button>
          </>
        }
      />

      {error ? (
        <div className="card">
          <EmptyState
            title="Unable to load tests"
            description={error}
            action={
              <div className="flex justify-center gap-4">
                <button className="btn btn-primary" onClick={fetchTests}>Retry</button>
                <Link to="/" className="btn btn-secondary no-underline">Go Back</Link>
              </div>
            }
          />
        </div>
      ) : isLoading ? (
        <>
          <div className="mb-6"><Skeleton variant="card" count={4} /></div>
          <Skeleton variant="row" count={5} />
        </>
      ) : tests.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<ListChecks size={40} />}
            title="No UI tests yet"
            description="Create your first UI automation test by recording interactions with your website."
            action={
              <button className="btn btn-primary mx-auto" onClick={() => setIsCreateOpen(true)}>
                <Plus size={18} /> Create UI Test
              </button>
            }
          />
        </div>
      ) : (
        <>
          <div className="flex gap-4 mb-6 flex-wrap">
            <StatCard icon={<ListChecks size={16} />} label="Total Tests" value={tests.length} />
            <StatCard icon={<CircleDot size={16} />} label="Recorded" value={recordedCount} tone="success" />
            <StatCard icon={<FileClock size={16} />} label="Draft (no steps yet)" value={draftCount} tone="warning" />
            <StatCard icon={<ListChecks size={16} />} label="Total Steps Recorded" value={totalStepsRecorded} tone="primary" />
          </div>

          <div className="flex items-center gap-4 mb-4 flex-wrap">
            <div className="search-box" style={{ flex: '1 1 260px' }}>
              <Search size={16} />
              <input
                className="input"
                placeholder="Search by name or URL…"
                value={search}
                onChange={e => setSearch(e.target.value)}
              />
            </div>
            <select className="select" style={{ width: 'auto', minWidth: 160 }} value={statusFilter} onChange={e => setStatusFilter(e.target.value as StatusFilter)}>
              <option value="all">All statuses</option>
              <option value="recorded">Recorded only</option>
              <option value="draft">Draft only</option>
            </select>
            <select className="select" style={{ width: 'auto', minWidth: 180 }} value={sortMode} onChange={e => setSortMode(e.target.value as SortMode)}>
              <option value="newest">Newest first</option>
              <option value="oldest">Oldest first</option>
              <option value="name-asc">Name A–Z</option>
              <option value="name-desc">Name Z–A</option>
            </select>
            <div className="flex" style={{ border: '1px solid var(--border-color)', borderRadius: 'var(--radius-md)', overflow: 'hidden' }}>
              <button
                className="btn-ghost" style={{ borderRadius: 0, background: viewMode === 'list' ? 'var(--bg-elevated)' : 'transparent', color: viewMode === 'list' ? 'var(--text-main)' : 'var(--text-muted)' }}
                onClick={() => setViewMode('list')} title="List view" aria-label="List view"
              >
                <List size={16} />
              </button>
              <button
                className="btn-ghost" style={{ borderRadius: 0, background: viewMode === 'grid' ? 'var(--bg-elevated)' : 'transparent', color: viewMode === 'grid' ? 'var(--text-main)' : 'var(--text-muted)' }}
                onClick={() => setViewMode('grid')} title="Grid view" aria-label="Grid view"
              >
                <LayoutGrid size={16} />
              </button>
            </div>
          </div>

          {visibleTests.length === 0 ? (
            <div className="card">
              <EmptyState title="No tests match your filters" description="Try a different search term or status filter." />
            </div>
          ) : viewMode === 'grid' ? (
            <div className="flex gap-4 flex-wrap">
              {visibleTests.map(test => {
                const hasSteps = !!(test.steps && test.steps.length > 0);
                return (
                  <div key={test.id} className="card" style={{ flex: '1 1 260px', maxWidth: 340 }}>
                    <div className="flex items-start justify-between mb-2">
                      <Link to={`/ui-automation/tests/${test.id}`} className="no-underline font-medium text-primary" style={{ fontSize: '1rem' }}>
                        {test.name}
                      </Link>
                      {hasSteps ? <span className="badge badge-success">Recorded</span> : <span className="badge badge-neutral">Draft</span>}
                    </div>
                    <div className="text-xs text-muted truncate mb-3">{test.targetUrl}</div>
                    <div className="flex items-center gap-4 text-xs text-subtle mb-4">
                      <span className="flex items-center gap-1"><ListChecks size={12} /> {test.steps ? test.steps.length : 0} steps</span>
                      <span className="flex items-center gap-1"><Clock size={12} /> {new Date(test.createdAt).toLocaleDateString()}</span>
                    </div>
                    <div className="flex gap-2">
                      <button
                        className="btn btn-secondary" style={{ flex: 1 }}
                        disabled={!hasSteps || runningId === test.id}
                        title={hasSteps ? 'Run this test' : 'Record steps before running'}
                        onClick={e => handleQuickRun(test, e)}
                      >
                        {runningId === test.id ? <RefreshCw size={15} className="animate-spin" /> : <Play size={15} />} Run
                      </button>
                      <Link to={`/ui-automation/tests/${test.id}`} className="btn btn-secondary no-underline">
                        <FileText size={15} />
                      </Link>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Test Name</th>
                    <th>Website</th>
                    <th>Status</th>
                    <th>Steps</th>
                    <th>Created</th>
                    <th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleTests.map(test => {
                    const hasSteps = !!(test.steps && test.steps.length > 0);
                    return (
                      <tr key={test.id}>
                        <td>
                          <Link to={`/ui-automation/tests/${test.id}`} className="no-underline font-medium text-primary">
                            {test.name}
                          </Link>
                        </td>
                        <td className="text-muted truncate" style={{ maxWidth: 260 }}>{test.targetUrl}</td>
                        <td>
                          {hasSteps ? (
                            <span className="badge badge-success">Recorded</span>
                          ) : (
                            <span className="badge badge-neutral">Draft</span>
                          )}
                        </td>
                        <td>{test.steps ? test.steps.length : 0}</td>
                        <td className="text-muted">
                          {new Date(test.createdAt).toLocaleDateString()}
                        </td>
                        <td style={{ textAlign: 'right' }}>
                          <div className="flex justify-end gap-2">
                            <button
                              className="btn btn-secondary"
                              style={{ padding: '0.25rem 0.5rem' }}
                              disabled={!hasSteps || runningId === test.id}
                              title={hasSteps ? 'Run this test' : 'Record steps before running'}
                              onClick={e => handleQuickRun(test, e)}
                            >
                              {runningId === test.id ? <RefreshCw size={16} className="animate-spin" /> : <Play size={16} />}
                            </button>
                            <Link to={`/ui-automation/tests/${test.id}`} className="btn btn-secondary" style={{ padding: '0.25rem 0.5rem' }} title="View test details">
                              <FileText size={16} />
                            </Link>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {isCreateOpen && (
        <CreateTestDialog onClose={() => setIsCreateOpen(false)} />
      )}
    </div>
  );
}
