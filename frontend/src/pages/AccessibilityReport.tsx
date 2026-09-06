import { useEffect, useMemo, useRef, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  AlertTriangle, Clock, Search, ChevronDown, ChevronUp, ExternalLink,
  RotateCw, ScanSearch, Code, Target as TargetIcon, Info,
} from 'lucide-react';
import { accessibilityApi } from '../services/accessibilityApi';
import type { AccessibilityScanRun, AccessibilityRuleFinding, AccessibilityPassSummary } from '../types';
import { getSeverityMeta, impactToSeverityKey, SEVERITY_ORDER, type SeverityKey } from '../utils/severity';
import PageHeader from '../components/PageHeader';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';
import EmailReportButton from '../components/EmailReportButton';
import ManualChecklist from '../components/ManualChecklist';
import type { ManualChecksMap } from '../types';

type FilterKey = 'all' | SeverityKey;

type ListItem =
  | { kind: 'finding'; severityKey: SeverityKey; finding: AccessibilityRuleFinding }
  | { kind: 'pass'; pass: AccessibilityPassSummary };

function parseJson<T>(json: string | undefined, fallback: T): T {
  if (!json) return fallback;
  try { return JSON.parse(json) as T; } catch { return fallback; }
}

export default function AccessibilityReport() {
  const { runId } = useParams();
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [run, setRun] = useState<AccessibilityScanRun | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [isRerunning, setIsRerunning] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const [filter, setFilter] = useState<FilterKey>('all');
  const [search, setSearch] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const fetchRun = async () => {
    if (!runId) return;
    try {
      const data = await accessibilityApi.getRun(Number(runId));
      setRun(data);
      if (data.status !== 'RUNNING' && pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    } catch {
      setError('Failed to load the accessibility report.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchRun();
    pollRef.current = setInterval(fetchRun, 3000);
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runId]);

  const violations = useMemo(() => parseJson<AccessibilityRuleFinding[]>(run?.violationsJson, []), [run]);
  const incomplete = useMemo(() => parseJson<AccessibilityRuleFinding[]>(run?.incompleteJson, []), [run]);
  const passes = useMemo(() => parseJson<AccessibilityPassSummary[]>(run?.passesSummaryJson, []), [run]);
  const manualChecks = useMemo(() => parseJson<ManualChecksMap>(run?.manualChecksJson, {}), [run]);

  const allItems: ListItem[] = useMemo(() => [
    ...violations.map(f => ({ kind: 'finding' as const, severityKey: impactToSeverityKey(f.impact), finding: f })),
    ...incomplete.map(f => ({ kind: 'finding' as const, severityKey: 'review' as SeverityKey, finding: f })),
    ...passes.map(p => ({ kind: 'pass' as const, pass: p })),
  ], [violations, incomplete, passes]);

  const visibleItems = useMemo(() => {
    let list = allItems;
    if (filter !== 'all') {
      list = list.filter(item => (item.kind === 'finding' ? item.severityKey : 'passed') === filter);
    }
    const query = search.trim().toLowerCase();
    if (query) {
      list = list.filter(item => {
        const ruleId = item.kind === 'finding' ? item.finding.ruleId : item.pass.ruleId;
        const description = item.kind === 'finding' ? item.finding.description : item.pass.description;
        return ruleId.toLowerCase().includes(query) || (description ?? '').toLowerCase().includes(query);
      });
    }
    return list;
  }, [allItems, filter, search]);

  const toggleExpanded = (key: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  const handleRerun = async () => {
    if (!run?.scan?.id) return;
    try {
      setIsRerunning(true);
      const newRun = await accessibilityApi.rerunScan(run.scan.id);
      showToast('Re-running scan…', 'success');
      navigate(`/accessibility/runs/${newRun.id}`);
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to re-run the scan.', 'error');
      setIsRerunning(false);
    }
  };

  if (isLoading) {
    return (
      <div className="container">
        <Skeleton variant="text" width="40%" height={32} />
        <div className="mt-6"><Skeleton variant="card" count={6} /></div>
      </div>
    );
  }

  if (error || !run) {
    return (
      <div className="container">
        <div className="card">
          <EmptyState title={error || 'Report not found'} action={<Link to="/accessibility" className="btn btn-secondary no-underline">Back to Accessibility</Link>} />
        </div>
      </div>
    );
  }

  const filterCounts: Record<FilterKey, number> = {
    all: allItems.length,
    critical: run.criticalCount,
    serious: run.seriousCount,
    moderate: run.moderateCount,
    minor: run.minorCount,
    review: run.needsReviewCount,
    passed: run.passedCount,
  };

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Accessibility', to: '/accessibility' }, { label: run.scan?.name ?? `Run #${run.id}` }]}
        title="Accessibility Scan Report"
        subtitle={<p style={{ margin: 0 }}>{run.scan?.name} &bull; <span className="font-mono">{run.targetUrl}</span></p>}
        actions={
          <>
            <StatusBadge status={run.status} size="lg" />
            {run.status !== 'RUNNING' && <EmailReportButton reportType="ACCESSIBILITY" runId={run.id} />}
            <button className="btn btn-secondary" onClick={handleRerun} disabled={isRerunning}>
              <RotateCw size={16} className={isRerunning ? 'animate-spin' : ''} /> Re-run
            </button>
          </>
        }
      />

      {run.status === 'RUNNING' && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--warning-bg)', borderColor: 'var(--warning)', color: 'var(--warning)', display: 'flex', alignItems: 'center', gap: '12px' }}>
          <ScanSearch size={18} style={{ flexShrink: 0 }} className="animate-pulse" />
          <span>Scanning {run.targetUrl}… this page updates automatically every 3 seconds.</span>
        </div>
      )}

      {run.status === 'FAILED' && run.errorMessage && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)', color: 'var(--error)', display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
          <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span>{run.errorMessage}</span>
        </div>
      )}

      {run.status === 'COMPLETED' && (
        <>
          <div className="flex items-center gap-2 mb-4 text-sm text-muted">
            <Clock size={14} /> Scan duration: {(run.durationMs / 1000).toFixed(1)}s
            <span style={{ marginLeft: 12 }}>•</span>
            <Info size={14} style={{ marginLeft: 12 }} />
            Automatically detected accessibility issues — automated tools cannot find every problem; manual review is still required for full coverage.
          </div>

          <ManualChecklist runId={run.id} initialChecks={manualChecks} />

          <div className="flex gap-3 mb-8 flex-wrap">
            {SEVERITY_ORDER.map(key => {
              const meta = getSeverityMeta(key);
              const Icon = meta.icon;
              return (
                <button
                  key={key}
                  onClick={() => setFilter(filter === key ? 'all' : key)}
                  className="card"
                  style={{
                    flex: '1 1 140px', cursor: 'pointer', textAlign: 'left',
                    border: `1px solid ${filter === key ? 'var(--border-color-strong)' : 'var(--border-color)'}`,
                    boxShadow: filter === key ? 'var(--shadow-lg)' : undefined,
                  }}
                >
                  <div className="flex items-center gap-2 mb-2 text-sm" style={{ color: `var(--severity-${key === 'passed' ? 'passed' : key})` }}>
                    <Icon size={16} /> {meta.label}
                  </div>
                  <h3 style={{ margin: 0 }}>{filterCounts[key]}</h3>
                </button>
              );
            })}
          </div>

          <div className="flex items-center gap-4 mb-4 flex-wrap">
            <div className="search-box" style={{ flex: '1 1 260px' }}>
              <Search size={15} />
              <input className="input" placeholder="Search violations by rule or description…" value={search} onChange={e => setSearch(e.target.value)} />
            </div>
            {filter !== 'all' && (
              <button className="btn btn-secondary" onClick={() => setFilter('all')} style={{ padding: '0.4rem 0.85rem' }}>
                Clear filter
              </button>
            )}
          </div>

          <div className="flex flex-col gap-3">
            {visibleItems.length === 0 ? (
              <div className="card"><EmptyState title="No issues match your filters" description={allItems.length === 0 ? 'No results in this scan yet.' : undefined} /></div>
            ) : (
              visibleItems.map((item, i) => {
                if (item.kind === 'pass') {
                  return (
                    <div key={`pass-${item.pass.ruleId}-${i}`} className="card violation-card" style={{ padding: '0.9rem 1.25rem' }}>
                      <div className="flex items-center justify-between gap-3 flex-wrap">
                        <div className="flex items-center gap-3">
                          <span className="badge badge-severity-passed">Passed</span>
                          <span className="font-medium">{item.pass.description || item.pass.ruleId}</span>
                        </div>
                        <span className="text-xs text-subtle">{item.pass.nodeCount} element{item.pass.nodeCount === 1 ? '' : 's'} checked</span>
                      </div>
                    </div>
                  );
                }

                const finding = item.finding;
                const key = `${item.severityKey}-${finding.ruleId}-${i}`;
                const isOpen = expanded.has(key);
                const meta = getSeverityMeta(item.severityKey);
                const wcagTags = (finding.tags || []).filter(t => t.startsWith('wcag'));

                return (
                  <div key={key} className={`card violation-card ${item.severityKey}`} style={{ padding: 0, overflow: 'hidden' }}>
                    <button
                      onClick={() => toggleExpanded(key)}
                      className="flex items-center justify-between gap-3 flex-wrap"
                      style={{ width: '100%', textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer', padding: '1rem 1.25rem', font: 'inherit', color: 'inherit' }}
                    >
                      <div style={{ flex: 1, minWidth: 260 }}>
                        <div className="flex items-center gap-2 mb-1 flex-wrap">
                          <span className={`badge ${meta.badgeClass}`}>{meta.label}</span>
                          <span className="font-medium">{finding.description || finding.ruleId}</span>
                        </div>
                        <div className="text-xs text-subtle font-mono">{finding.ruleId}</div>
                      </div>
                      <div className="flex items-center gap-4 text-sm text-muted">
                        <span>{finding.nodes.length} affected element{finding.nodes.length === 1 ? '' : 's'}</span>
                        {isOpen ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </div>
                    </button>

                    {isOpen && (
                      <div style={{ padding: '0 1.25rem 1.25rem', borderTop: '1px solid var(--border-color)' }}>
                        <p className="text-sm mt-4" style={{ margin: '1rem 0 0.5rem' }}>{finding.help}</p>

                        {wcagTags.length > 0 && (
                          <div className="flex items-center gap-2 flex-wrap mb-4">
                            <span className="text-xs text-subtle">WCAG:</span>
                            {wcagTags.map(tag => <span key={tag} className="badge badge-neutral" style={{ textTransform: 'none' }}>{tag}</span>)}
                          </div>
                        )}

                        <div className="flex flex-col gap-3">
                          {finding.nodes.map((node, ni) => (
                            <div key={ni} style={{ background: 'var(--bg-input)', borderRadius: 'var(--radius-md)', padding: '0.85rem' }}>
                              <div className="flex items-center gap-2 text-xs text-muted mb-2">
                                <TargetIcon size={12} /> <span className="font-mono">{node.target}</span>
                              </div>
                              {node.html && (
                                <div className="flex items-start gap-2 mb-2">
                                  <Code size={12} className="text-subtle" style={{ marginTop: 3, flexShrink: 0 }} />
                                  <code className="text-xs font-mono" style={{ color: 'var(--text-muted)', wordBreak: 'break-all' }}>{node.html}</code>
                                </div>
                              )}
                              {node.failureSummary && (
                                <div className="text-xs" style={{ color: 'var(--text-muted)', whiteSpace: 'pre-line' }}>{node.failureSummary}</div>
                              )}
                            </div>
                          ))}
                        </div>

                        {finding.helpUrl && (
                          <a href={finding.helpUrl} target="_blank" rel="noopener noreferrer" className="flex items-center gap-1 text-sm text-primary mt-4" style={{ width: 'fit-content' }}>
                            View rule documentation <ExternalLink size={13} />
                          </a>
                        )}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
        </>
      )}
    </div>
  );
}
