import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ChevronDown, ChevronUp, Clock, ScanSearch } from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import type { ApiRun } from '../types';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';
import StatusBadge from '../components/StatusBadge';
import MethodBadge from '../components/MethodBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import EmailReportButton from '../components/EmailReportButton';
import ApiResponseViewer from '../components/ApiResponseViewer';

export default function ApiRunReport() {
  const { runId } = useParams();
  const [run, setRun] = useState<ApiRun | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<Set<number>>(new Set());
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchRun = async () => {
    if (!runId) return;
    try {
      const data = await apiTestingApi.getRun(Number(runId));
      setRun(data);
      if (data.status !== 'RUNNING' && pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    } catch {
      setError('Failed to load the API run report.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchRun();
    pollRef.current = setInterval(fetchRun, 2000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);

  const toggle = (id: number) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  if (isLoading) {
    return (
      <div className="container">
        <Skeleton variant="text" width="40%" height={32} />
        <div className="mt-6"><Skeleton variant="card" count={4} /></div>
      </div>
    );
  }

  if (error || !run) {
    return (
      <div className="container">
        <div className="card"><EmptyState title={error || 'Report not found'} action={<Link to="/api-testing" className="btn btn-secondary no-underline">Back to API Testing</Link>} /></div>
      </div>
    );
  }

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[
          { label: 'Dashboard', to: '/' }, { label: 'API Testing', to: '/api-testing' },
          ...(run.collection ? [{ label: run.collection.name, to: `/api-testing/collections/${run.collection.id}` }] : []),
          { label: `Run #${run.id}` },
        ]}
        title="API Test Report"
        subtitle={<p style={{ margin: 0 }}>{run.runName} &bull; <span className="font-mono">{run.environment?.name ?? 'No environment'}</span></p>}
        actions={
          <>
            <StatusBadge status={run.status} size="lg" />
            {run.status !== 'RUNNING' && <EmailReportButton reportType="API_TESTING" runId={run.id} />}
          </>
        }
      />

      {run.status === 'RUNNING' && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--warning-bg)', borderColor: 'var(--warning)', color: 'var(--warning)', display: 'flex', alignItems: 'center', gap: 12 }}>
          <ScanSearch size={18} className="animate-pulse" />
          <span>Run in progress — this page updates automatically every 2 seconds.</span>
        </div>
      )}

      {run.errorMessage && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)', color: 'var(--error)' }}>{run.errorMessage}</div>
      )}

      <div className="flex items-center gap-2 mb-4 text-sm text-muted">
        <Clock size={14} /> Duration: {(run.totalDurationMs / 1000).toFixed(1)}s
      </div>

      <div className="flex gap-4 mb-8 flex-wrap">
        <StatCard label="Total Requests" value={run.totalRequests} />
        <StatCard label="Passed" value={run.passedRequests} tone="success" />
        <StatCard label="Failed" value={run.failedRequests} tone={run.failedRequests > 0 ? 'error' : undefined} />
        <StatCard label="Iterations" value={run.iterationCount} />
      </div>

      <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
        Request Results ({run.requestResults.length})
      </h3>

      <div className="flex flex-col gap-3">
        {run.requestResults.length === 0 ? (
          <div className="card"><EmptyState title="No requests executed yet." /></div>
        ) : (
          run.requestResults.map((r, i) => {
            const isOpen = expanded.has(r.id);
            return (
              <div key={r.id} className="card" style={{ padding: 0, overflow: 'hidden' }}>
                <button
                  onClick={() => toggle(r.id)}
                  className="flex items-center justify-between gap-3 flex-wrap"
                  style={{ width: '100%', textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer', padding: '1rem 1.25rem', font: 'inherit', color: 'inherit' }}
                >
                  <div style={{ flex: 1, minWidth: 260 }}>
                    <div className="flex items-center gap-2 mb-1 flex-wrap">
                      <span className="text-xs text-subtle font-mono">{String(i + 1).padStart(2, '0')}</span>
                      <MethodBadge method={r.method} />
                      <span className="font-medium">{r.requestName}</span>
                      {run.iterationCount > 1 || run.datasetFilename ? <span className="text-xs text-subtle">iter {r.iterationIndex + 1}</span> : null}
                    </div>
                    <div className="text-xs text-subtle font-mono truncate" style={{ maxWidth: 500 }}>{r.resolvedUrl}</div>
                  </div>
                  <div className="flex items-center gap-4 text-sm text-muted">
                    <StatusBadge status={r.status} size="sm" />
                    <span>{r.durationMs}ms</span>
                    {isOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                  </div>
                </button>
                {isOpen && (
                  <div style={{ borderTop: '1px solid var(--border-color)', height: 420 }}>
                    <ApiResponseViewer result={r} />
                  </div>
                )}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
