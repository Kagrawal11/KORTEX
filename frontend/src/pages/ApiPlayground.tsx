import { useMemo, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Send, Save, Loader2, Info, Globe, FileEdit } from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import { emptyRequestSpec } from '../types';
import type { ApiRequestSpec, ApiRequestRunResult, HttpMethod } from '../types';
import PageHeader from '../components/PageHeader';
import { METHOD_COLOR } from '../components/MethodBadge';
import RequestEditorTabs from '../components/RequestEditorTabs';
import ApiResponseViewer from '../components/ApiResponseViewer';
import ResizableSplit from '../components/ResizableSplit';
import SaveRequestDialog from '../components/SaveRequestDialog';
import { useToast } from '../components/Toast';

const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];

function initialSpec(exampleSpec?: ApiRequestSpec): ApiRequestSpec {
  if (exampleSpec) return exampleSpec;
  const spec = emptyRequestSpec('New Request');
  spec.authType = 'NONE'; // no collection to inherit from yet — INHERIT would be a confusing default here
  return spec;
}

/**
 * The no-setup-required entry point: "New Request" from the API Testing
 * dashboard lands here, NOT inside a collection. A first-time user can type
 * a URL and hit Send immediately — Collections and Environments only enter
 * the picture once they explicitly choose to (Save Request / the
 * environment selector), never as a precondition.
 */
export default function ApiPlayground() {
  const location = useLocation();
  const navigate = useNavigate();
  const { showToast } = useToast();

  const exampleSpec = (location.state as { exampleSpec?: ApiRequestSpec } | null)?.exampleSpec;
  const isExample = !!exampleSpec;

  const [spec, setSpec] = useState<ApiRequestSpec>(() => initialSpec(exampleSpec));
  const [isSending, setIsSending] = useState(false);
  const [lastResult, setLastResult] = useState<ApiRequestRunResult | null>(null);
  const [showSaveDialog, setShowSaveDialog] = useState(false);

  const usesVariables = useMemo(() => /\{\{\s*[^{}$]+\s*\}\}/.test(spec.url), [spec.url]);

  const handleSend = async () => {
    if (!spec.url.trim()) { showToast('Enter a URL before sending.', 'error'); return; }
    try {
      setIsSending(true);
      const run = await apiTestingApi.execute({ requestName: spec.name, request: spec });
      setLastResult(run.requestResults[0] ?? null);
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to send the request.', 'error');
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div className="container" style={{ maxWidth: 'none' }}>
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'API Testing', to: '/api-testing' }, { label: 'New Request' }]}
        title="New Request"
        subtitle={<p style={{ margin: 0 }}>Enter a URL and click Send — no collection or environment required to get started.</p>}
      />

      {isExample && (
        <div className="card mb-4" style={{ backgroundColor: 'var(--info-bg)', color: 'var(--info)', display: 'flex', alignItems: 'center', gap: 10 }}>
          <Info size={16} style={{ flexShrink: 0 }} />
          <span>This is an example request against a real public API (GitHub). Send it, inspect the real response, add an assertion, then Save Request when you're ready.</span>
        </div>
      )}

      <div className="api-workspace" style={{ height: 'calc(100vh - 220px)' }}>
        <div className="api-main" style={{ width: '100%' }}>
          <ResizableSplit
            top={
              <div>
                <div className="api-editor-toolbar">
                  <select
                    className="select api-method-select"
                    style={{ color: METHOD_COLOR[spec.method] ?? undefined }}
                    value={spec.method}
                    onChange={e => setSpec({ ...spec, method: e.target.value as HttpMethod })}
                  >
                    {METHODS.map(m => <option key={m} value={m} style={{ color: METHOD_COLOR[m] }}>{m}</option>)}
                  </select>
                  <div className="search-box" style={{ flex: '1 1 260px' }}>
                    <Globe size={15} />
                    <input className="input api-url-input" placeholder="https://api.example.com/users" value={spec.url} onChange={e => setSpec({ ...spec, url: e.target.value })} />
                  </div>
                  <button className="btn btn-primary api-send-btn" onClick={handleSend} disabled={isSending}>
                    {isSending ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />} Send Request
                  </button>
                  <button className="btn btn-secondary" onClick={() => setShowSaveDialog(true)}>
                    <Save size={15} /> Save Request
                  </button>
                </div>
                {usesVariables && (
                  <div className="flex items-center gap-2 text-xs" style={{ padding: '0.5rem 1rem 0', color: 'var(--warning)' }}>
                    <Info size={13} />
                    <span><code className="font-mono">{'{{...}}'}</code> in your URL is an environment variable — save this request into a collection and select an environment to resolve it, or replace it with a real value for now.</span>
                  </div>
                )}
                <div className="api-request-name-row">
                  <FileEdit size={14} style={{ color: 'var(--text-subtle)', flexShrink: 0 }} />
                  <input className="input" style={{ maxWidth: 320, fontWeight: 500, fontSize: '0.85rem' }} value={spec.name} onChange={e => setSpec({ ...spec, name: e.target.value })} placeholder="Request name" />
                </div>
                <RequestEditorTabs spec={spec} onChange={setSpec} />
              </div>
            }
            bottom={<ApiResponseViewer result={lastResult} />}
          />
        </div>
      </div>

      {showSaveDialog && (
        <SaveRequestDialog
          spec={spec}
          onClose={() => setShowSaveDialog(false)}
          onSaved={(requestId, collectionId) => {
            setShowSaveDialog(false);
            navigate(`/api-testing/collections/${collectionId}`, { state: { openRequestId: requestId } });
          }}
        />
      )}
    </div>
  );
}
