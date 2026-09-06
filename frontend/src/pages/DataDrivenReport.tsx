import { useEffect, useRef, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { dataDrivenApi } from '../services/uiAutomationApi';
import type { DataDrivenRun } from '../types';
import {
  CheckCircle2, XCircle, Clock, RefreshCw,
  ChevronDown, ChevronRight, AlertTriangle, Database, Zap, ArrowDownToLine
} from 'lucide-react';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import StatCard from '../components/StatCard';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import EmailReportButton from '../components/EmailReportButton';

export default function DataDrivenReport() {
  const { runId }  = useParams();
  const [run, setRun]           = useState<DataDrivenRun | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError]         = useState('');
  const [expandedRows, setExpandedRows] = useState<Set<number>>(new Set());
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const rowRefs = useRef<Map<number, HTMLDivElement>>(new Map());

  const fetchRun = async () => {
    if (!runId) return;
    try {
      const data = await dataDrivenApi.getRun(Number(runId));
      setRun(data);
      if (data.status !== 'RUNNING' && pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    } catch {
      setError('Failed to load data-driven run report.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchRun();
    pollRef.current = setInterval(fetchRun, 3000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
  }, [runId]);

  const toggleRow = (rowNumber: number) => {
    setExpandedRows(prev => {
      const next = new Set(prev);
      if (next.has(rowNumber)) next.delete(rowNumber);
      else next.add(rowNumber);
      return next;
    });
  };

  const parseJson = (json: string | undefined) => {
    if (!json) return null;
    try { return JSON.parse(json); } catch { return null; }
  };

  const jumpToFirstFailure = () => {
    if (!run) return;
    const firstFailedRow = run.rowResults.find(r => r.status !== 'SUCCESS');
    if (!firstFailedRow) return;
    setExpandedRows(prev => new Set(prev).add(firstFailedRow.rowNumber));
    requestAnimationFrame(() => {
      rowRefs.current.get(firstFailedRow.rowNumber)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    });
  };

  if (isLoading) {
    return (
      <div className="container">
        <Skeleton variant="text" width="40%" height={32} />
        <div className="mt-6"><Skeleton variant="card" count={5} /></div>
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

  const passRate = run.totalRows > 0
    ? Math.round((run.passedRows / run.totalRows) * 100)
    : 0;

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'UI Automation', to: '/ui-automation' }, { label: run.scenario.name, to: `/ui-automation/tests/${run.scenario.id}` }, { label: `Run #${run.id}` }]}
        title={
          <span className="flex items-center gap-2">
            <Database size={22} className="text-primary" /> Data-Driven Report
          </span>
        }
        subtitle={
          <>
            <p style={{ margin: 0 }}>Run #{run.id} &bull; {run.scenario.name} &bull; {run.datasetFilename}</p>
            <p className="text-sm" style={{ margin: '0.25rem 0 0' }}>Loop: Steps {run.startStepOrder} → {run.endStepOrder}</p>
          </>
        }
        actions={
          <>
            <StatusBadge status={run.status} size="lg" />
            {run.status !== 'RUNNING' && <EmailReportButton reportType="DATA_DRIVEN" runId={run.id} />}
          </>
        }
      />

      {/* Running banner */}
      {run.status === 'RUNNING' && (
        <div className="card mb-6" style={{
          backgroundColor: 'rgba(245,158,11,0.08)', borderColor: 'var(--warning)',
          color: 'var(--warning)', display: 'flex', alignItems: 'center', gap: '12px'
        }}>
          <RefreshCw size={18} style={{ animation: 'spin 1s linear infinite', flexShrink: 0 }} />
          <span>Data-driven run in progress. This page updates every 3 seconds…</span>
        </div>
      )}

      {/* Run-level error summary — e.g. pre-loop failure, or the run stopped
          early because a between-rows page reset never succeeded (fewer rows
          were attempted than the dataset actually contains). */}
      {run.status !== 'RUNNING' && run.errorMessage && (
        <div className="card mb-6" style={{
          backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)',
          color: 'var(--error)', display: 'flex', alignItems: 'flex-start', gap: '12px'
        }}>
          <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span>{run.errorMessage}</span>
        </div>
      )}

      {/* Summary cards */}
      <div className="flex gap-4 mb-8 flex-wrap">
        <StatCard icon={<Clock size={16} />} label="Duration" value={(run.totalDurationMs / 1000).toFixed(2) + 's'} />
        <StatCard icon={<Database size={16} />} label="Total Rows" value={run.totalRows} tone="primary" />
        <StatCard icon={<CheckCircle2 size={16} />} label="Passed Rows" value={run.passedRows} tone="success" />
        <StatCard icon={<XCircle size={16} />} label="Failed Rows" value={run.failedRows} tone="error" />
        <StatCard icon={<Zap size={16} />} label="Pass Rate" value={passRate + '%'} tone={passRate === 100 ? 'success' : passRate >= 50 ? 'warning' : 'error'} />
      </div>

      {/* Progress bar */}
      {run.totalRows > 0 && (
        <div className="card mb-6" style={{ padding: '1.25rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.5rem', fontSize: '0.875rem' }}>
            <span>Row Progress</span>
            <span style={{ color: 'var(--text-muted)' }}>{run.passedRows + run.failedRows} / {run.totalRows} completed</span>
          </div>
          <div style={{ height: '10px', background: 'var(--bg-input)', borderRadius: '999px', overflow: 'hidden', display: 'flex' }}>
            <div style={{
              width: `${(run.passedRows / run.totalRows) * 100}%`,
              background: 'var(--success)', transition: 'width 0.4s ease'
            }} />
            <div style={{
              width: `${(run.failedRows / run.totalRows) * 100}%`,
              background: 'var(--error)', transition: 'width 0.4s ease'
            }} />
          </div>
          <div style={{ display: 'flex', gap: '1rem', marginTop: '0.4rem', fontSize: '0.75rem', color: 'var(--text-muted)' }}>
            <span style={{ color: 'var(--success)' }}>● Passed: {run.passedRows}</span>
            <span style={{ color: 'var(--error)' }}>● Failed: {run.failedRows}</span>
            {run.totalRows - run.passedRows - run.failedRows > 0 && (
              <span style={{ color: 'var(--warning)' }}>
                ● {run.status === 'RUNNING' ? 'Pending' : 'Never attempted'}: {run.totalRows - run.passedRows - run.failedRows}
              </span>
            )}
          </div>
        </div>
      )}

      {/* Row-level timeline */}
      <div className="card">
        <div className="flex items-center justify-between mb-6">
          <h2 style={{ margin: 0 }}>Row Execution Timeline</h2>
          {run.failedRows > 0 && (
            <button className="btn btn-secondary" onClick={jumpToFirstFailure} style={{ padding: '0.4rem 0.85rem' }}>
              <ArrowDownToLine size={15} /> Jump to first failure
            </button>
          )}
        </div>

        {run.rowResults.length === 0 ? (
          <EmptyState title={run.status === 'RUNNING' ? 'Rows will appear here as they execute…' : 'No rows executed.'} />
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
            {run.rowResults.map(row => {
              const rowData = parseJson(row.rowDataJson) as Record<string, string> | null;
              const stepResults = parseJson(row.stepResultsJson) as Array<{
                stepOrder: number; actionType: string; status: string;
                durationMs: number; errorMessage?: string;
              }> | null;
              const isExpanded = expandedRows.has(row.rowNumber);

              return (
                <div
                  key={row.id || row.rowNumber}
                  ref={el => { if (el) rowRefs.current.set(row.rowNumber, el); }}
                  style={{
                    display: 'flex',
                    border: `1px solid ${row.status === 'FAILED' || row.status === 'CRITICAL_FAILURE' ? 'var(--error)' : row.status === 'SUCCESS' ? 'rgba(16,185,129,0.3)' : 'var(--border-color)'}`,
                    borderRadius: 'var(--radius-md)',
                    overflow: 'hidden',
                    background: 'var(--bg-input)',
                  }}
                >
                  <div className={`timeline-rail ${row.status === 'SUCCESS' ? 'success' : row.status === 'CRITICAL_FAILURE' ? 'warning' : 'error'}`} />
                  <div style={{ flex: 1, minWidth: 0 }}>
                  {/* Row header */}
                  <div
                    onClick={() => toggleRow(row.rowNumber)}
                    className="dd-row-header"
                    style={{
                      display: 'flex', alignItems: 'center', gap: '0.75rem',
                      padding: '0.75rem 1rem', cursor: 'pointer',
                      background: row.status === 'SUCCESS'
                        ? 'rgba(16,185,129,0.05)'
                        : row.status !== 'CRITICAL_FAILURE'
                          ? 'rgba(239,68,68,0.05)' : 'transparent',
                    }}
                  >
                    {/* Expand icon */}
                    {isExpanded
                      ? <ChevronDown size={16} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                      : <ChevronRight size={16} style={{ color: 'var(--text-muted)', flexShrink: 0 }} />
                    }

                    {/* Status icon */}
                    {row.status === 'SUCCESS'
                      ? <CheckCircle2 size={18} style={{ color: 'var(--success)', flexShrink: 0 }} />
                      : row.status === 'CRITICAL_FAILURE'
                        ? <AlertTriangle size={18} style={{ color: 'var(--warning)', flexShrink: 0 }} />
                        : <XCircle size={18} style={{ color: 'var(--error)', flexShrink: 0 }} />
                    }

                    <span style={{ fontWeight: 600, minWidth: '70px' }}>Row {row.rowNumber}</span>

                    {/* Row data preview */}
                    {rowData && (
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)', flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {Object.entries(rowData).slice(0, 3).map(([k, v]) => `${k}: ${v}`).join(' | ')}
                      </span>
                    )}

                    <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem', marginLeft: 'auto', flexShrink: 0 }}>
                      <StatusBadge status={row.status} size="sm" />
                      <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>{row.durationMs}ms</span>
                    </div>
                  </div>

                  {/* Expanded detail */}
                  {isExpanded && (
                    <div style={{ padding: '1rem', borderTop: '1px solid var(--border-color)' }}>
                      {/* Row input data */}
                      {rowData && Object.keys(rowData).length > 0 && (
                        <div style={{ marginBottom: '1rem' }}>
                          <div style={{ fontSize: '0.78rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '0.5rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            Input Data
                          </div>
                          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.5rem' }}>
                            {Object.entries(rowData).map(([k, v]) => (
                              <div key={k} style={{
                                background: 'var(--bg-panel)', borderRadius: 'var(--radius-sm)',
                                padding: '0.3rem 0.65rem', fontSize: '0.8rem',
                                border: '1px solid var(--border-color)'
                              }}>
                                <span style={{ color: 'var(--text-muted)' }}>{k}: </span>
                                <span style={{ fontWeight: 500 }}>{v || '—'}</span>
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Step results */}
                      {stepResults && stepResults.length > 0 && (
                        <div>
                          <div style={{ fontSize: '0.78rem', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '0.5rem', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                            Step Results
                          </div>
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.4rem' }}>
                            {stepResults.map(sr => (
                              <div key={sr.stepOrder} style={{
                                display: 'flex', alignItems: 'center', gap: '0.75rem',
                                padding: '0.4rem 0.75rem',
                                background: sr.status === 'FAILED' ? 'rgba(239,68,68,0.07)' : 'transparent',
                                borderRadius: 'var(--radius-sm)',
                                fontSize: '0.85rem',
                              }}>
                                {sr.status === 'PASSED' || sr.status === 'HEALED_BY_AI'
                                  ? <CheckCircle2 size={15} style={{ color: sr.status === 'HEALED_BY_AI' ? 'var(--warning)' : 'var(--success)', flexShrink: 0 }} />
                                  : <XCircle size={15} style={{ color: 'var(--error)', flexShrink: 0 }} />
                                }
                                <span style={{ color: 'var(--text-muted)', minWidth: '65px' }}>Step {sr.stepOrder}</span>
                                <span style={{ color: 'var(--primary)' }}>{sr.actionType}</span>
                                <span style={{ marginLeft: 'auto', color: 'var(--text-muted)', fontSize: '0.78rem' }}>{sr.durationMs}ms</span>
                                {sr.errorMessage && (
                                  <span style={{ color: 'var(--error)', fontSize: '0.8rem', marginLeft: '0.5rem', flex: 1, textAlign: 'right' }}>
                                    {sr.errorMessage.substring(0, 80)}
                                  </span>
                                )}
                              </div>
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Error summary */}
                      {row.errorMessage && row.status !== 'SUCCESS' && (
                        <div style={{
                          marginTop: '0.75rem', padding: '0.65rem 0.85rem',
                          background: 'var(--error-bg)', color: 'var(--error)',
                          borderRadius: 'var(--radius-md)', fontSize: '0.85rem',
                        }}>
                          <strong>Error{row.failedAtStep > 0 ? ` at step ${row.failedAtStep}` : ''}:</strong> {row.errorMessage}
                        </div>
                      )}
                    </div>
                  )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Step-level summary */}
      <div className="card" style={{ marginTop: '1.5rem' }}>
        <h2 className="mb-4" style={{ fontSize: '1.1rem' }}>Overall Step Summary</h2>
        <div style={{ display: 'flex', gap: '1.5rem', flexWrap: 'wrap' }}>
          {[
            { label: 'Total Steps', value: run.totalSteps, color: 'var(--text-main)' },
            { label: 'Passed', value: run.passedSteps, color: 'var(--success)' },
            { label: 'Failed', value: run.failedSteps, color: 'var(--error)' },
            { label: 'AI Healed', value: run.healedByAiSteps, color: 'var(--warning)' },
          ].map(({ label, value, color }) => (
            <div key={label} style={{ fontSize: '0.9rem' }}>
              <span style={{ color: 'var(--text-muted)' }}>{label}: </span>
              <span style={{ fontWeight: 700, color }}>{value}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
