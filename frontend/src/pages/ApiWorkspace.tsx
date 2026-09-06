import { useEffect, useState } from 'react';
import { useParams, useLocation, Link } from 'react-router-dom';
import {
  Send, Save, Plus, FolderPlus, FolderOpen, ChevronRight, ChevronDown, Trash2, Copy,
  Play, Settings2, MoreVertical, Loader2, Info, Globe, FileEdit,
} from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import { emptyRequestSpec } from '../types';
import type {
  ApiCollection, ApiFolder, ApiRequestEntity, ApiEnvironment, ApiRequestSpec,
  ApiAuthConfig, ApiRequestRunResult, HttpMethod,
} from '../types';
import PageHeader from '../components/PageHeader';
import MethodBadge, { METHOD_COLOR } from '../components/MethodBadge';
import RequestEditorTabs from '../components/RequestEditorTabs';
import ApiResponseViewer from '../components/ApiResponseViewer';
import ResizableSplit from '../components/ResizableSplit';
import CollectionRunnerDialog from '../components/CollectionRunnerDialog';
import EmailReportButton from '../components/EmailReportButton';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS'];

export default function ApiWorkspace() {
  const { collectionId } = useParams();
  const cid = Number(collectionId);
  const location = useLocation();
  const { showToast } = useToast();

  const [collection, setCollection] = useState<ApiCollection | null>(null);
  const [folders, setFolders] = useState<ApiFolder[]>([]);
  const [requests, setRequests] = useState<ApiRequestEntity[]>([]);
  const [environments, setEnvironments] = useState<ApiEnvironment[]>([]);
  const [environmentId, setEnvironmentId] = useState<number | null>(null);
  const [collectionAuth, setCollectionAuth] = useState<ApiAuthConfig>({ type: 'NONE' });
  const [expandedFolders, setExpandedFolders] = useState<Set<number>>(new Set());

  const [selectedRequestId, setSelectedRequestId] = useState<number | null>(null);
  const [spec, setSpec] = useState<ApiRequestSpec>(emptyRequestSpec());
  const [savedSnapshot, setSavedSnapshot] = useState(JSON.stringify(emptyRequestSpec()));
  const isDirty = JSON.stringify(spec) !== savedSnapshot;

  const [isLoading, setIsLoading] = useState(true);
  const [isSending, setIsSending] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [lastResult, setLastResult] = useState<ApiRequestRunResult | null>(null);
  const [showRunner, setShowRunner] = useState(false);
  const [lastRunId, setLastRunId] = useState<number | null>(null);

  const refreshTree = async () => {
    const [f, r] = await Promise.all([apiTestingApi.getFolders(cid), apiTestingApi.getRequestsForCollection(cid)]);
    setFolders(f);
    setRequests(r);
    return r;
  };

  useEffect(() => {
    (async () => {
      try {
        setIsLoading(true);
        const [c, f, r, envs] = await Promise.all([
          apiTestingApi.getCollection(cid),
          apiTestingApi.getFolders(cid),
          apiTestingApi.getRequestsForCollection(cid),
          apiTestingApi.getEnvironments(),
        ]);
        setCollection(c);
        setFolders(f);
        setRequests(r);
        setEnvironments(envs);
        setCollectionAuth(JSON.parse(c.authConfigJson || '{}'));
        setExpandedFolders(new Set(f.map(folder => folder.id)));

        // Arriving here right after "Save Request" from the Playground —
        // open that exact request instead of leaving the editor empty.
        const openId = (location.state as { openRequestId?: number } | null)?.openRequestId;
        if (openId != null) {
          const spec = await apiTestingApi.getRequestSpec(openId);
          setSelectedRequestId(openId);
          setSpec(spec);
          setSavedSnapshot(JSON.stringify(spec));
        }
      } catch {
        showToast('Failed to load collection.', 'error');
      } finally {
        setIsLoading(false);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cid]);

  const openRequest = async (id: number) => {
    if (isDirty && !window.confirm('Discard unsaved changes to the current request?')) return;
    try {
      const s = await apiTestingApi.getRequestSpec(id);
      setSelectedRequestId(id);
      setSpec(s);
      setSavedSnapshot(JSON.stringify(s));
      setLastResult(null);
    } catch {
      showToast('Failed to load request.', 'error');
    }
  };

  const handleNewRequest = async (folderId: number | null) => {
    try {
      const created = await apiTestingApi.createRequest(cid, folderId, emptyRequestSpec('New Request'));
      await refreshTree();
      openRequest(created.id);
    } catch {
      showToast('Failed to create request.', 'error');
    }
  };

  const handleNewFolder = async () => {
    const name = window.prompt('Folder name:', 'New Folder');
    if (!name?.trim()) return;
    try {
      await apiTestingApi.createFolder(cid, name.trim());
      await refreshTree();
    } catch {
      showToast('Failed to create folder.', 'error');
    }
  };

  const handleRenameFolder = async (folder: ApiFolder) => {
    const name = window.prompt('Rename folder:', folder.name);
    if (!name?.trim() || name.trim() === folder.name) return;
    try {
      await apiTestingApi.renameFolder(folder.id, name.trim());
      await refreshTree();
    } catch {
      showToast('Failed to rename folder.', 'error');
    }
  };

  const handleDeleteFolder = async (folder: ApiFolder) => {
    if (!window.confirm(`Delete folder "${folder.name}"? Its requests will move to the collection root.`)) return;
    try {
      await apiTestingApi.deleteFolder(folder.id);
      await refreshTree();
    } catch {
      showToast('Failed to delete folder.', 'error');
    }
  };

  const handleDuplicateRequest = async (id: number, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      const copy = await apiTestingApi.duplicateRequest(id);
      await refreshTree();
      showToast(`Duplicated as "${copy.name}".`, 'success');
    } catch {
      showToast('Failed to duplicate request.', 'error');
    }
  };

  const handleDeleteRequest = async (id: number, name: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm(`Delete request "${name}"?`)) return;
    try {
      await apiTestingApi.deleteRequest(id);
      if (selectedRequestId === id) { setSelectedRequestId(null); setSpec(emptyRequestSpec()); setSavedSnapshot(JSON.stringify(emptyRequestSpec())); }
      await refreshTree();
    } catch {
      showToast('Failed to delete request.', 'error');
    }
  };

  const handleMoveRequest = async (id: number, folderId: number | null) => {
    try {
      await apiTestingApi.moveRequest(id, folderId);
      await refreshTree();
      showToast('Request moved.', 'success');
    } catch {
      showToast('Failed to move request.', 'error');
    }
  };

  const handleSave = async () => {
    if (selectedRequestId == null || !spec.name.trim()) {
      if (!spec.name.trim()) showToast('Request name is required.', 'error');
      return;
    }
    try {
      setIsSaving(true);
      await apiTestingApi.updateRequest(selectedRequestId, spec);
      setSavedSnapshot(JSON.stringify(spec));
      await refreshTree();
      showToast('Request saved.', 'success');
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to save request.', 'error');
    } finally {
      setIsSaving(false);
    }
  };

  const handleSend = async () => {
    if (!spec.url.trim()) { showToast('Enter a URL before sending.', 'error'); return; }
    try {
      setIsSending(true);
      const run = await apiTestingApi.execute({
        requestId: selectedRequestId ?? undefined,
        requestName: spec.name,
        collectionId: cid,
        environmentId: environmentId ?? undefined,
        request: spec,
      });
      setLastResult(run.requestResults[0] ?? null);
      setLastRunId(run.id);
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to send the request.', 'error');
    } finally {
      setIsSending(false);
    }
  };

  const toggleFolder = (id: number) => {
    setExpandedFolders(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  if (isLoading) {
    return (
      <div className="container">
        <Skeleton variant="text" width="40%" height={32} />
        <div className="mt-6"><Skeleton variant="card" count={1} /></div>
      </div>
    );
  }

  if (!collection) {
    return <div className="container"><div className="card">Collection not found.</div></div>;
  }

  const rootRequests = requests.filter(r => !r.folder);

  return (
    <div className="container" style={{ maxWidth: 'none' }}>
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'API Testing', to: '/api-testing' }, { label: collection.name }]}
        title={collection.name}
        subtitle={collection.description ? <p style={{ margin: 0 }}>{collection.description}</p> : undefined}
        actions={
          <>
            <select className="select" style={{ width: 'auto', minWidth: 160 }} value={environmentId ?? ''} onChange={e => setEnvironmentId(e.target.value ? Number(e.target.value) : null)}>
              <option value="">No environment</option>
              {environments.map(env => <option key={env.id} value={env.id}>{env.name}</option>)}
            </select>
            <Link to="/api-testing/environments" className="btn btn-secondary no-underline"><Settings2 size={16} /></Link>
            <button className="btn btn-secondary" onClick={() => setShowRunner(true)}><Play size={16} /> Run Collection</button>
            {lastRunId && <EmailReportButton reportType="API_TESTING" runId={lastRunId} />}
          </>
        }
      />

      <div className="api-workspace">
        <div className="api-tree-sidebar">
          <div className="api-tree-header">
            <h3>Requests</h3>
            <div className="flex gap-1">
              <button className="btn-ghost" title="New folder" onClick={handleNewFolder}><FolderPlus size={15} /></button>
              <button className="btn-ghost" title="New request" onClick={() => handleNewRequest(null)}><Plus size={15} /></button>
            </div>
          </div>
          <div className="api-tree-body">
            {requests.length === 0 && folders.length === 0 ? (
              <div className="api-tree-empty">No requests yet.<br />Click + to add one.</div>
            ) : (
              <>
                {folders.map(folder => (
                  <div key={folder.id} className="mb-1">
                    <div className="api-tree-folder-row" onClick={() => toggleFolder(folder.id)}>
                      {expandedFolders.has(folder.id) ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
                      <span style={{ flex: 1 }}>{folder.name}</span>
                      <button className="btn-ghost" style={{ width: 22, height: 22 }} onClick={e => { e.stopPropagation(); handleNewRequest(folder.id); }} title="Add request"><Plus size={12} /></button>
                      <button className="btn-ghost" style={{ width: 22, height: 22 }} onClick={e => { e.stopPropagation(); handleRenameFolder(folder); }} title="Rename"><MoreVertical size={12} /></button>
                      <button className="btn-ghost" style={{ width: 22, height: 22 }} onClick={e => { e.stopPropagation(); handleDeleteFolder(folder); }} title="Delete"><Trash2 size={12} /></button>
                    </div>
                    {expandedFolders.has(folder.id) && (
                      <div className="api-tree-folder-children">
                        {requests.filter(r => r.folder?.id === folder.id).map(r => (
                          <RequestTreeRow
                            key={r.id} request={r} active={selectedRequestId === r.id}
                            onOpen={() => openRequest(r.id)}
                            onDuplicate={e => handleDuplicateRequest(r.id, e)}
                            onDelete={e => handleDeleteRequest(r.id, r.name, e)}
                            onMove={folderId => handleMoveRequest(r.id, folderId)}
                            folders={folders}
                          />
                        ))}
                      </div>
                    )}
                  </div>
                ))}
                {rootRequests.map(r => (
                  <RequestTreeRow
                    key={r.id} request={r} active={selectedRequestId === r.id}
                    onOpen={() => openRequest(r.id)}
                    onDuplicate={e => handleDuplicateRequest(r.id, e)}
                    onDelete={e => handleDeleteRequest(r.id, r.name, e)}
                    onMove={folderId => handleMoveRequest(r.id, folderId)}
                    folders={folders}
                  />
                ))}
              </>
            )}
          </div>
        </div>

        <div className="api-main">
          {selectedRequestId == null ? (
            <div className="api-response-empty">
              <FolderOpen size={28} />
              <div className="api-empty-title">Select a request, or create a new one</div>
              <div className="api-empty-sub">Pick something from the sidebar, or start a fresh request right here.</div>
              <button className="btn btn-primary mt-2" onClick={() => handleNewRequest(null)}><Plus size={15} /> New Request</button>
            </div>
          ) : (
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
                      <input className="input api-url-input" placeholder="https://{{baseUrl}}/endpoint" value={spec.url} onChange={e => setSpec({ ...spec, url: e.target.value })} />
                    </div>
                    <button className="btn btn-primary api-send-btn" onClick={handleSend} disabled={isSending}>
                      {isSending ? <Loader2 size={15} className="animate-spin" /> : <Send size={15} />} Send Request
                    </button>
                    <button className="btn btn-secondary" onClick={handleSave} disabled={isSaving || !isDirty}>
                      <Save size={15} /> {isDirty ? 'Save Request*' : 'Saved'}
                    </button>
                  </div>
                  {/\{\{\s*[^{}$]+\s*\}\}/.test(spec.url) && environmentId == null && (
                    <div className="flex items-center gap-2 text-xs" style={{ padding: '0.5rem 1rem 0', color: 'var(--warning)' }}>
                      <Info size={13} />
                      <span><code className="font-mono">{'{{...}}'}</code> in your URL is an environment variable — select an environment above to resolve it.</span>
                    </div>
                  )}
                  <div className="api-request-name-row">
                    <FileEdit size={14} style={{ color: 'var(--text-subtle)', flexShrink: 0 }} />
                    <input
                      className="input"
                      style={{ maxWidth: 320, fontWeight: 500, fontSize: '0.85rem' }}
                      value={spec.name}
                      onChange={e => setSpec({ ...spec, name: e.target.value })}
                      placeholder="Request name"
                    />
                  </div>
                  <RequestEditorTabs spec={spec} onChange={setSpec} collectionAuthType={collectionAuth.type} />
                </div>
              }
              bottom={<ApiResponseViewer result={lastResult} />}
            />
          )}
        </div>
      </div>

      {showRunner && (
        <CollectionRunnerDialog
          collectionId={cid}
          folders={folders}
          requests={requests}
          environments={environments}
          defaultEnvironmentId={environmentId}
          onClose={() => setShowRunner(false)}
        />
      )}
    </div>
  );
}

function RequestTreeRow({ request, active, onOpen, onDuplicate, onDelete, onMove, folders }: {
  request: ApiRequestEntity; active: boolean; onOpen: () => void;
  onDuplicate: (e: React.MouseEvent) => void; onDelete: (e: React.MouseEvent) => void;
  onMove: (folderId: number | null) => void; folders: ApiFolder[];
}) {
  return (
    <div className={`api-tree-item ${active ? 'active' : ''}`} onClick={onOpen}>
      <MethodBadge method={request.method} size="sm" />
      <span className="api-tree-request-name" title={request.name}>{request.name}</span>
      {folders.length > 0 && (
        <select
          className="select"
          style={{ width: 22, height: 22, padding: 0, border: 'none', background: 'none', opacity: 0.6 }}
          value={request.folder?.id ?? ''}
          onClick={e => e.stopPropagation()}
          onChange={e => onMove(e.target.value ? Number(e.target.value) : null)}
          title="Move to folder"
        >
          <option value="">Root</option>
          {folders.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}
        </select>
      )}
      <button className="btn-ghost" style={{ width: 22, height: 22 }} onClick={onDuplicate} title="Duplicate"><Copy size={11} /></button>
      <button className="btn-ghost" style={{ width: 22, height: 22 }} onClick={onDelete} title="Delete"><Trash2 size={11} /></button>
    </div>
  );
}
