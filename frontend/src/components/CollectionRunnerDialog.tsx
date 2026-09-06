import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { X, Play, CheckCircle2, XCircle, Loader2 } from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import type { ApiEnvironment, ApiFolder, ApiRequestEntity, ApiRun, ApiRunScope } from '../types';
import { useToast } from './Toast';

interface CollectionRunnerDialogProps {
  collectionId: number;
  folders: ApiFolder[];
  requests: ApiRequestEntity[];
  environments: ApiEnvironment[];
  defaultEnvironmentId: number | null;
  onClose: () => void;
}

export default function CollectionRunnerDialog({ collectionId, folders, requests, environments, defaultEnvironmentId, onClose }: CollectionRunnerDialogProps) {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [scope, setScope] = useState<ApiRunScope>('COLLECTION');
  const [folderId, setFolderId] = useState<number | null>(folders[0]?.id ?? null);
  const [environmentId, setEnvironmentId] = useState<number | null>(defaultEnvironmentId);
  const [iterationCount, setIterationCount] = useState(1);
  const [stopOnFailure, setStopOnFailure] = useState(true);
  const [delayMs, setDelayMs] = useState(0);
  const [datasetFile, setDatasetFile] = useState<File | null>(null);

  const [run, setRun] = useState<ApiRun | null>(null);
  const [isStarting, setIsStarting] = useState(false);
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => () => { if (pollRef.current) clearInterval(pollRef.current); }, []);

  const plannedTotal = (scope === 'REQUEST' ? 1 : scope === 'FOLDER' ? requests.filter(r => r.folder?.id === folderId).length : requests.length)
    * (datasetFile ? 1 : Math.max(1, iterationCount)); // dataset row count isn't known client-side; iterations shown as configured until the run reports real totals

  const handleStart = async () => {
    setIsStarting(true);
    try {
      const started = await apiTestingApi.startRun(collectionId, {
        environmentId: environmentId ?? undefined,
        scope,
        folderId: scope === 'FOLDER' ? folderId ?? undefined : undefined,
        requestId: scope === 'REQUEST' ? requests[0]?.id : undefined,
        iterationCount,
        stopOnFailure,
        delayMs,
      }, datasetFile);
      setRun(started);
      pollRef.current = setInterval(async () => {
        try {
          const updated = await apiTestingApi.getRun(started.id);
          setRun(updated);
          if (updated.status !== 'RUNNING' && pollRef.current) {
            clearInterval(pollRef.current);
            pollRef.current = null;
          }
        } catch {
          if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
        }
      }, 1200);
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to start the run.', 'error');
      setIsStarting(false);
    }
  };

  const isRunning = run?.status === 'RUNNING';
  const isDone = run != null && run.status !== 'RUNNING';

  return (
    <div className="modal-overlay">
      <div className="card modal-panel" style={{ maxWidth: 560 }}>
        <div className="flex items-center justify-between mb-4">
          <h2>Collection Runner</h2>
          <button className="btn-ghost" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>

        {!run ? (
          <>
            <div className="flex flex-col gap-4">
              <div>
                <label className="label">Scope</label>
                <select className="select" value={scope} onChange={e => setScope(e.target.value as ApiRunScope)}>
                  <option value="COLLECTION">Entire Collection ({requests.length} request{requests.length === 1 ? '' : 's'})</option>
                  {folders.length > 0 && <option value="FOLDER">A Folder</option>}
                </select>
              </div>
              {scope === 'FOLDER' && (
                <div>
                  <label className="label">Folder</label>
                  <select className="select" value={folderId ?? ''} onChange={e => setFolderId(Number(e.target.value))}>
                    {folders.map(f => <option key={f.id} value={f.id}>{f.name} ({requests.filter(r => r.folder?.id === f.id).length})</option>)}
                  </select>
                </div>
              )}
              <div>
                <label className="label">Environment</label>
                <select className="select" value={environmentId ?? ''} onChange={e => setEnvironmentId(e.target.value ? Number(e.target.value) : null)}>
                  <option value="">No environment</option>
                  {environments.map(env => <option key={env.id} value={env.id}>{env.name}</option>)}
                </select>
              </div>
              <div className="flex gap-4">
                <div style={{ flex: 1 }}>
                  <label className="label">Iterations</label>
                  <input className="input" type="number" min={1} value={iterationCount} onChange={e => setIterationCount(Math.max(1, Number(e.target.value)))} disabled={!!datasetFile} />
                </div>
                <div style={{ flex: 1 }}>
                  <label className="label">Delay Between Requests (ms)</label>
                  <input className="input" type="number" min={0} value={delayMs} onChange={e => setDelayMs(Math.max(0, Number(e.target.value)))} />
                </div>
              </div>
              <label className="flex items-center gap-2" style={{ cursor: 'pointer' }}>
                <input type="checkbox" checked={stopOnFailure} onChange={e => setStopOnFailure(e.target.checked)} />
                Stop on first failure
              </label>
              <div>
                <label className="label">Data-Driven Dataset (optional, CSV or XLSX)</label>
                <input type="file" accept=".csv,.xlsx" onChange={e => setDatasetFile(e.target.files?.[0] ?? null)} />
                {datasetFile && <p className="text-xs text-subtle mt-1">One iteration per row — overrides the iteration count above.</p>}
              </div>
            </div>
            <div className="flex justify-between items-center mt-6 pt-4 border-t">
              <button className="btn btn-secondary" onClick={onClose}>Cancel</button>
              <button className="btn btn-primary" onClick={handleStart} disabled={isStarting}>
                <Play size={14} /> {isStarting ? 'Starting…' : `Run (${plannedTotal} planned)`}
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="mb-4">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm text-muted">
                  {isRunning ? `Request ${run.requestResults.length}${run.datasetFilename ? '' : ' / ' + plannedTotal}` : 'Completed'}
                </span>
                {isRunning && <Loader2 size={16} className="animate-spin" />}
              </div>
              {isRunning && (
                <div className="collection-runner-progress-bar">
                  <div className="collection-runner-progress-fill" style={{ width: `${Math.min(100, (run.requestResults.length / Math.max(1, plannedTotal)) * 100)}%` }} />
                </div>
              )}
            </div>

            <div className="flex flex-col gap-1" style={{ maxHeight: 260, overflowY: 'auto' }}>
              {run.requestResults.map(r => (
                <div key={r.id} className="flex items-center gap-2 text-sm" style={{ padding: '0.3rem 0' }}>
                  {r.status === 'PASSED' ? <CheckCircle2 size={15} color="var(--success)" /> : <XCircle size={15} color="var(--error)" />}
                  <span>{r.requestName}</span>
                  <span className="text-xs text-subtle">{r.durationMs}ms</span>
                </div>
              ))}
            </div>

            {isDone && (
              <div className="flex gap-4 mt-4 pt-4 border-t">
                <div><div className="text-xs text-subtle">Total</div><div className="font-medium">{run.totalRequests}</div></div>
                <div><div className="text-xs text-subtle">Passed</div><div className="font-medium" style={{ color: 'var(--success)' }}>{run.passedRequests}</div></div>
                <div><div className="text-xs text-subtle">Failed</div><div className="font-medium" style={{ color: 'var(--error)' }}>{run.failedRequests}</div></div>
                <div><div className="text-xs text-subtle">Duration</div><div className="font-medium">{(run.totalDurationMs / 1000).toFixed(1)}s</div></div>
              </div>
            )}

            <div className="flex justify-between items-center mt-6 pt-4 border-t">
              <button className="btn btn-secondary" onClick={onClose}>Close</button>
              {isDone && (
                <button className="btn btn-primary" onClick={() => navigate(`/api-testing/runs/${run.id}`)}>View Report</button>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
