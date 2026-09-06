import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestRun } from '../types';
import { CheckCircle2, XCircle, AlertTriangle, Clock, Zap, RefreshCw, ArrowDownToLine } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import StatCard from '../components/StatCard';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import EmailReportButton from '../components/EmailReportButton';

export default function TestReport() {
  const { runId } = useParams();
  const [run, setRun] = useState<TestRun | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const stepRefs = useRef<Map<number, HTMLDivElement>>(new Map());

  const fetchRun = async () => {
    if (!runId) return;
    try {
      const data = await uiAutomationApi.getTestRun(Number(runId));
      setRun(data);
      // Stop polling once run is no longer RUNNING
      if (data.status !== 'RUNNING' && pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    } catch {
      setError('Failed to load test report.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchRun();
    // Poll every 3 seconds for live status updates
    pollRef.current = setInterval(fetchRun, 3000);
    return () => {
      if (pollRef.current) clearInterval(pollRef.current);
    };
  }, [runId]);

  const jumpToFirstFailure = () => {
    if (!run) return;
    const firstFailed = run.stepResults?.find(s => s.status === 'FAILED');
    if (!firstFailed) return;
    stepRefs.current.get(firstFailed.id)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
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
        <div className="card">
          <EmptyState
            title={error || 'Report not found'}
            action={<Link to="/ui-automation" className="btn btn-secondary no-underline">Back to Dashboard</Link>}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'UI Automation', to: '/ui-automation' }, { label: run.scenario.name, to: `/ui-automation/tests/${run.scenario.id}` }, { label: `Run #${run.id}` }]}
        title="Execution Report"
        subtitle={<p style={{ margin: 0 }}>Run #{run.id} &bull; {run.scenario.name}</p>}
        actions={
          <>
            <StatusBadge status={run.status} size="lg" />
            {run.status !== 'RUNNING' && <EmailReportButton reportType="STANDARD" runId={run.id} />}
          </>
        }
      />

      {run.status === 'RUNNING' && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--warning-bg)', borderColor: 'var(--warning)', color: 'var(--warning)', display: 'flex', alignItems: 'center', gap: '12px' }}>
          <RefreshCw size={18} className="animate-spin" style={{ flexShrink: 0 }} />
          <span>Test is running in the background. This page will update automatically every 3 seconds…</span>
        </div>
      )}

      {/* Run-level error — the backend already returns this for a FAILED run;
          it simply wasn't surfaced anywhere before. Renders nothing if absent. */}
      {run.status !== 'RUNNING' && run.errorMessage && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)', color: 'var(--error)', display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
          <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span>{run.errorMessage}</span>
        </div>
      )}

      {/* Summary Cards */}
      <div className="flex gap-4 mb-8 flex-wrap">
        <StatCard icon={<Clock size={16} />} label="Duration" value={`${(run.totalDurationMs / 1000).toFixed(2)}s`} flex="1 1 200px" />
        <StatCard icon={<CheckCircle2 size={16} className="text-emerald-500" />} label="Passed Steps" value={`${run.passedSteps} / ${run.totalSteps}`} tone="success" flex="1 1 200px" />
        <StatCard icon={<Zap size={16} className="text-blue-500" />} label="AI Healed" value={run.healedByAiSteps} tone="primary" flex="1 1 200px" />
        <StatCard icon={<XCircle size={16} className="text-red-500" />} label="Failed Steps" value={run.failedSteps} tone="error" flex="1 1 200px" />
      </div>

      {/* Execution Timeline */}
      <div className="card">
        <div className="flex items-center justify-between mb-6">
          <h2 style={{ margin: 0 }}>Execution Timeline</h2>
          {run.failedSteps > 0 && (
            <button className="btn btn-secondary" onClick={jumpToFirstFailure} style={{ padding: '0.4rem 0.85rem' }}>
              <ArrowDownToLine size={15} /> Jump to failure
            </button>
          )}
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {run.stepResults && run.stepResults.length > 0 ? (
            run.stepResults.map((step) => (
              <div
                key={step.id}
                ref={el => { if (el) stepRefs.current.set(step.id, el); }}
                style={{ display: 'flex', gap: '1rem', padding: '1rem', backgroundColor: 'var(--bg-input)', borderRadius: 'var(--radius-md)', border: `1px solid ${step.status === 'FAILED' ? 'var(--error)' : 'var(--border-color)'}` }}>
                <div className={`timeline-rail ${step.status === 'PASSED' ? 'success' : step.status === 'FAILED' ? 'error' : step.status === 'HEALED_BY_AI' ? 'warning' : 'neutral'}`} />
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '30px', flexShrink: 0 }}>
                  {step.status === 'PASSED' && <CheckCircle2 className="text-emerald-500" />}
                  {step.status === 'FAILED' && <XCircle className="text-red-500" />}
                  {step.status === 'HEALED_BY_AI' && <AlertTriangle className="text-amber-500" />}
                  {step.status === 'SKIPPED' && <span className="badge badge-neutral" style={{ padding: '2px 6px', fontSize: '0.6rem' }}>—</span>}
                </div>

                <div style={{ flex: 1 }}>
                  <div className="flex items-center justify-between mb-2">
                    <h4 style={{ margin: 0 }}>
                      Step {step.stepOrder}: <span className="text-primary">{step.actionType}</span>
                    </h4>
                    <span className="text-subtle" style={{ fontSize: '0.85rem' }}>{step.durationMs}ms</span>
                  </div>

                  <div className="font-mono text-sm text-muted" style={{ backgroundColor: 'rgba(0,0,0,0.2)', padding: '0.5rem', borderRadius: 'var(--radius-sm)' }}>
                    {step.primarySelector} {step.inputValue && `→ "${step.inputValue}"`}
                  </div>

                  {step.status === 'HEALED_BY_AI' && (
                    <div className="text-sm" style={{ marginTop: '0.75rem', padding: '0.75rem', backgroundColor: 'var(--warning-bg)', color: 'var(--warning)', borderRadius: 'var(--radius-md)' }}>
                      <strong>AI Self-Healing:</strong> The original selector failed, but the AI successfully located the element using heuristics.
                    </div>
                  )}

                  {step.status === 'FAILED' && step.errorMessage && (
                    <div className="text-sm" style={{ marginTop: '0.75rem', padding: '0.75rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)' }}>
                      <strong>Error:</strong> {step.errorMessage}
                    </div>
                  )}
                </div>
              </div>
            ))
          ) : (
            <EmptyState title="No steps were recorded during this execution." />
          )}
        </div>
      </div>
    </div>
  );
}
