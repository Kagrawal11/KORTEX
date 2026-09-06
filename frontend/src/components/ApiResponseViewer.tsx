import { useMemo, useState } from 'react';
import { Copy, Clock, HardDrive, FileJson, FileCode, ListTree, CheckCircle2, Send as SendIcon } from 'lucide-react';
import type { ApiRequestRunResult, AssertionResult } from '../types';
import JsonTreeView from './JsonTreeView';
import { useToast } from './Toast';

type ResponseTab = 'pretty' | 'raw' | 'headers' | 'tests';

function parseJsonSafe(text: string): unknown | null {
  try { return JSON.parse(text); } catch { return null; }
}

function parseHeaders(json: string): Record<string, string> {
  try { return JSON.parse(json); } catch { return {}; }
}

function parseAssertions(json: string): AssertionResult[] {
  try { return JSON.parse(json); } catch { return []; }
}

function isSuccessStatus(status: number): boolean {
  return status > 0 && status < 400;
}

export default function ApiResponseViewer({ result }: { result: ApiRequestRunResult | null }) {
  const { showToast } = useToast();
  const [tab, setTab] = useState<ResponseTab>('pretty');

  const parsedBody = useMemo(() => (result ? parseJsonSafe(result.responseBody) : null), [result]);
  const headers = useMemo(() => (result ? parseHeaders(result.responseHeadersJson) : {}), [result]);
  const assertions = useMemo(() => (result ? parseAssertions(result.assertionResultsJson) : []), [result]);

  if (!result) {
    return (
      <div className="api-response-empty">
        <SendIcon size={28} />
        <div className="api-empty-title">Send your first API request</div>
        <div className="api-empty-sub">Enter an endpoint above and click Send Request — the response will appear right here.</div>
      </div>
    );
  }

  const isNetworkFailure = result.status === 'NETWORK_ERROR' || result.status === 'TIMEOUT';
  const pillClass = isNetworkFailure ? 'error' : isSuccessStatus(result.httpStatus) ? 'success' : 'error';

  const handleCopyRaw = async () => {
    try {
      await navigator.clipboard.writeText(result.responseBody);
      showToast('Response copied to clipboard.', 'success');
    } catch {
      showToast('Could not copy to clipboard.', 'error');
    }
  };

  return (
    <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <div className="api-response-status-bar">
        <span className={`api-status-pill ${pillClass}`}>
          {isNetworkFailure ? result.status.replace('_', ' ') : `${result.httpStatus} ${result.httpStatusText}`}
        </span>
        <span className="api-metric"><Clock size={13} /> {result.durationMs}ms</span>
        {!isNetworkFailure && (
          <span className="api-metric"><HardDrive size={13} /> {(result.responseSizeBytes / 1024).toFixed(2)} KB{result.responseTruncated ? ' (truncated)' : ''}</span>
        )}
        {assertions.length > 0 && (
          <span className="api-metric" style={{ color: assertions.every(a => a.passed) ? 'var(--success)' : 'var(--error)' }}>
            <CheckCircle2 size={13} /> {assertions.filter(a => a.passed).length} / {assertions.length} assertions passed
          </span>
        )}
      </div>

      {isNetworkFailure && result.errorMessage && (
        <div className="m-4" style={{ padding: '0.75rem 1rem', background: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)', fontSize: '0.85rem', fontFamily: 'var(--font-mono)', whiteSpace: 'pre-wrap' }}>
          {result.errorMessage}
        </div>
      )}

      {!isNetworkFailure && (
        <>
          <div className="tabs-bar" style={{ marginBottom: 0, padding: '0 0.25rem' }}>
            <button className={`tab-btn ${tab === 'pretty' ? 'active' : ''}`} onClick={() => setTab('pretty')}><FileJson size={14} /> Pretty</button>
            <button className={`tab-btn ${tab === 'raw' ? 'active' : ''}`} onClick={() => setTab('raw')}><FileCode size={14} /> Raw</button>
            <button className={`tab-btn ${tab === 'headers' ? 'active' : ''}`} onClick={() => setTab('headers')}>
              <ListTree size={14} /> Headers <span className="badge-count">{Object.keys(headers).length}</span>
            </button>
            <button className={`tab-btn ${tab === 'tests' ? 'active' : ''}`} onClick={() => setTab('tests')}>
              <CheckCircle2 size={14} /> Test Results {assertions.length > 0 && <span className="badge-count">{assertions.length}</span>}
            </button>
          </div>

          <div style={{ flex: 1, overflow: 'auto', padding: '1rem' }}>
            {tab === 'pretty' && (
              parsedBody !== null
                ? <JsonTreeView value={parsedBody} />
                : <div className="json-tree" style={{ whiteSpace: 'pre-wrap' }}>{result.responseBody || <span className="text-subtle">Empty response body.</span>}</div>
            )}
            {tab === 'raw' && (
              <div>
                <div className="flex justify-end mb-2">
                  <button className="btn btn-secondary" style={{ padding: '0.3rem 0.6rem' }} onClick={handleCopyRaw}><Copy size={13} /> Copy</button>
                </div>
                <pre className="json-tree" style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{result.responseBody}</pre>
              </div>
            )}
            {tab === 'headers' && (
              <div className="table-wrapper" style={{ border: 'none' }}>
                <table>
                  <thead><tr><th>Name</th><th>Value</th></tr></thead>
                  <tbody>
                    {Object.entries(headers).map(([k, v]) => (
                      <tr key={k}><td className="font-mono text-xs">{k}</td><td className="font-mono text-xs" style={{ wordBreak: 'break-all' }}>{v}</td></tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            {tab === 'tests' && (
              assertions.length === 0 ? (
                <div className="api-response-empty" style={{ height: 'auto', padding: '1.5rem 0' }}>
                  <CheckCircle2 size={24} />
                  <div className="api-empty-sub">No assertions configured yet — add one in the "Add Assertion" tab above.</div>
                </div>
              ) : (
                <div>
                  {assertions.map((a, i) => (
                    <div key={i} className="assertion-line">
                      <span className="assertion-dot" style={{ background: a.passed ? 'var(--success)' : 'var(--error)' }} />
                      <div>
                        <div style={{ color: a.passed ? 'var(--text-main)' : 'var(--error)' }}>{a.description}</div>
                        {!a.passed && a.expected !== undefined && (
                          <div className="text-xs text-subtle">expected "{a.expected}", received "{a.actual}"</div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )
            )}
          </div>
        </>
      )}
    </div>
  );
}
