import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestRun } from '../types';
import { ArrowLeft, CheckCircle2, XCircle, AlertTriangle, Clock, Zap } from 'lucide-react';

export default function TestReport() {
  const { runId } = useParams();
  const [run, setRun] = useState<TestRun | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    if (runId) {
      uiAutomationApi.getTestRun(Number(runId))
        .then(setRun)
        .catch(() => setError('Failed to load test report.'))
        .finally(() => setIsLoading(false));
    }
  }, [runId]);

  if (isLoading) {
    return <div className="container" style={{ textAlign: 'center', paddingTop: '4rem' }}>Loading report...</div>;
  }

  if (error || !run) {
    return (
      <div className="container" style={{ textAlign: 'center', paddingTop: '4rem' }}>
        <h3 style={{ color: 'var(--error)' }}>{error || 'Report not found'}</h3>
        <Link to="/ui-automation" className="btn btn-secondary mt-4" style={{ textDecoration: 'none' }}>Back to Dashboard</Link>
      </div>
    );
  }

  return (
    <div className="container">
      <div className="mb-4">
        <Link to={`/ui-automation/tests/${run.scenario.id}`} className="flex items-center gap-2" style={{ color: 'var(--text-muted)', textDecoration: 'none', display: 'inline-flex' }}>
          <ArrowLeft size={16} /> Back to Test
        </Link>
      </div>

      <div className="flex items-start justify-between mb-8">
        <div>
          <h1 className="mb-2">Execution Report</h1>
          <p style={{ color: 'var(--text-muted)' }}>Run #{run.id} • {run.scenario.name}</p>
        </div>
        <div>
          {run.status === 'PASSED' && <span className="badge badge-success" style={{ fontSize: '1.25rem', padding: '0.5rem 1rem' }}>PASSED</span>}
          {run.status === 'FAILED' && <span className="badge badge-error" style={{ fontSize: '1.25rem', padding: '0.5rem 1rem' }}>FAILED</span>}
          {run.status === 'RUNNING' && <span className="badge badge-warning" style={{ fontSize: '1.25rem', padding: '0.5rem 1rem' }}>RUNNING</span>}
        </div>
      </div>

      {/* Summary Cards */}
      <div className="flex gap-4 mb-8" style={{ flexWrap: 'wrap' }}>
        <div className="card" style={{ flex: '1 1 200px' }}>
          <div className="flex items-center gap-2 mb-2" style={{ color: 'var(--text-muted)' }}>
            <Clock size={16} /> <span>Duration</span>
          </div>
          <h3>{(run.totalDurationMs / 1000).toFixed(2)}s</h3>
        </div>
        <div className="card" style={{ flex: '1 1 200px' }}>
          <div className="flex items-center gap-2 mb-2" style={{ color: 'var(--text-muted)' }}>
            <CheckCircle2 size={16} className="text-emerald-500" /> <span>Passed Steps</span>
          </div>
          <h3 style={{ color: 'var(--success)' }}>{run.passedSteps} / {run.totalSteps}</h3>
        </div>
        <div className="card" style={{ flex: '1 1 200px' }}>
          <div className="flex items-center gap-2 mb-2" style={{ color: 'var(--text-muted)' }}>
            <Zap size={16} className="text-blue-500" /> <span>AI Healed</span>
          </div>
          <h3 style={{ color: 'var(--primary)' }}>{run.healedByAiSteps}</h3>
        </div>
        <div className="card" style={{ flex: '1 1 200px' }}>
          <div className="flex items-center gap-2 mb-2" style={{ color: 'var(--text-muted)' }}>
            <XCircle size={16} className="text-red-500" /> <span>Failed Steps</span>
          </div>
          <h3 style={{ color: 'var(--error)' }}>{run.failedSteps}</h3>
        </div>
      </div>

      {/* Execution Timeline */}
      <div className="card">
        <h2 className="mb-6">Execution Timeline</h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '1rem' }}>
          {run.stepResults && run.stepResults.length > 0 ? (
            run.stepResults.map((step) => (
              <div key={step.id} style={{ display: 'flex', gap: '1rem', padding: '1rem', backgroundColor: 'var(--bg-input)', borderRadius: 'var(--radius-md)', border: `1px solid ${step.status === 'FAILED' ? 'var(--error)' : 'var(--border-color)'}` }}>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', width: '40px' }}>
                  {step.status === 'PASSED' && <CheckCircle2 className="text-emerald-500" />}
                  {step.status === 'FAILED' && <XCircle className="text-red-500" />}
                  {step.status === 'HEALED_BY_AI' && <AlertTriangle className="text-amber-500" />}
                  <div style={{ flex: 1, width: '2px', backgroundColor: 'var(--border-color)', margin: '0.5rem 0' }}></div>
                </div>
                
                <div style={{ flex: 1 }}>
                  <div className="flex items-center justify-between mb-2">
                    <h4 style={{ margin: 0 }}>
                      Step {step.stepOrder}: <span style={{ color: 'var(--primary)' }}>{step.actionType}</span>
                    </h4>
                    <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>{step.durationMs}ms</span>
                  </div>
                  
                  <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)', fontFamily: 'monospace', backgroundColor: 'rgba(0,0,0,0.2)', padding: '0.5rem', borderRadius: 'var(--radius-sm)' }}>
                    {step.primarySelector} {step.inputValue && `→ "${step.inputValue}"`}
                  </div>

                  {step.status === 'HEALED_BY_AI' && (
                    <div style={{ marginTop: '0.75rem', padding: '0.75rem', backgroundColor: 'rgba(245, 158, 11, 0.1)', color: 'var(--warning)', borderRadius: 'var(--radius-md)', fontSize: '0.875rem' }}>
                      <strong>AI Self-Healing:</strong> The original selector failed, but the AI successfully located the element using heuristics.
                    </div>
                  )}

                  {step.status === 'FAILED' && step.errorMessage && (
                    <div style={{ marginTop: '0.75rem', padding: '0.75rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)', fontSize: '0.875rem' }}>
                      <strong>Error:</strong> {step.errorMessage}
                    </div>
                  )}
                </div>
              </div>
            ))
          ) : (
            <p style={{ color: 'var(--text-muted)' }}>No steps were recorded during this execution.</p>
          )}
        </div>
      </div>
    </div>
  );
}
