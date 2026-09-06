import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Plus, FolderOpen, Settings2, Upload, Copy, Trash2, X, Send, FlaskConical,
} from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import { emptyRequestSpec } from '../types';
import type { ApiCollection, ApiRun, ApiRequestSpec } from '../types';
import PageHeader from '../components/PageHeader';
import StatCard from '../components/StatCard';
import StatusBadge from '../components/StatusBadge';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

/** A real, safe public API — GitHub's — so a first-time user can see a genuine response with zero setup. Clearly an example, never treated as real saved/execution data until the user explicitly saves it. */
function exampleRequestSpec(): ApiRequestSpec {
  const spec = emptyRequestSpec('Get Example Repo (GitHub)');
  spec.method = 'GET';
  spec.url = 'https://api.github.com/repos/octocat/Spoon-Knife';
  spec.authType = 'NONE';
  return spec;
}

function CreateCollectionDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (c: ApiCollection) => void }) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) { setError('Collection name is required.'); return; }
    try {
      setIsSubmitting(true);
      setError('');
      const collection = await apiTestingApi.createCollection(name.trim(), description.trim() || undefined);
      onCreated(collection);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to create collection.');
      setIsSubmitting(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="card modal-panel">
        <div className="flex items-center justify-between mb-4">
          <h2>New Collection</h2>
          <button className="btn-ghost" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <p className="mb-6">A collection groups related API requests into a real, saved test suite.</p>
        {error && <div className="mb-4" style={{ padding: '0.75rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)' }}>{error}</div>}
        <form onSubmit={handleSubmit}>
          <div className="mb-4">
            <label className="label">Collection Name</label>
            <input className="input" placeholder="e.g. Auth API Tests" value={name} onChange={e => setName(e.target.value)} disabled={isSubmitting} autoFocus />
          </div>
          <div className="mb-6">
            <label className="label">Description (optional)</label>
            <input className="input" placeholder="What does this collection test?" value={description} onChange={e => setDescription(e.target.value)} disabled={isSubmitting} />
          </div>
          <div className="flex justify-between items-center mt-6 pt-4 border-t">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={isSubmitting}>Cancel</button>
            <button type="submit" className="btn btn-primary" disabled={isSubmitting}>{isSubmitting ? 'Creating…' : 'Create Collection'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

function ImportDialog({ onClose, onImported }: { onClose: () => void; onImported: (c: ApiCollection) => void }) {
  const [kind, setKind] = useState<'postman' | 'openapi'>('postman');
  const [file, setFile] = useState<File | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');

  const handleImport = async () => {
    if (!file) { setError('Choose a file to import.'); return; }
    try {
      setIsSubmitting(true);
      setError('');
      const collection = kind === 'postman' ? await apiTestingApi.importPostman(file) : await apiTestingApi.importOpenApi(file);
      onImported(collection);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Import failed.');
      setIsSubmitting(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="card modal-panel">
        <div className="flex items-center justify-between mb-4">
          <h2>Import Collection</h2>
          <button className="btn-ghost" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <p className="mb-6">Import a Postman v2.1 collection or an OpenAPI 3.0 (JSON) specification.</p>
        {error && <div className="mb-4" style={{ padding: '0.75rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)' }}>{error}</div>}
        <div className="mb-4 flex gap-2">
          <button className={`btn ${kind === 'postman' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setKind('postman')} disabled={isSubmitting}>Postman Collection</button>
          <button className={`btn ${kind === 'openapi' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setKind('openapi')} disabled={isSubmitting}>OpenAPI (JSON)</button>
        </div>
        <div className="mb-6">
          <input type="file" accept=".json" onChange={e => setFile(e.target.files?.[0] ?? null)} disabled={isSubmitting} />
        </div>
        <div className="flex justify-between items-center mt-6 pt-4 border-t">
          <button type="button" className="btn btn-secondary" onClick={onClose} disabled={isSubmitting}>Cancel</button>
          <button className="btn btn-primary" onClick={handleImport} disabled={isSubmitting || !file}>{isSubmitting ? 'Importing…' : 'Import'}</button>
        </div>
      </div>
    </div>
  );
}

export default function ApiTestingDashboard() {
  const navigate = useNavigate();
  const { showToast } = useToast();
  const [collections, setCollections] = useState<ApiCollection[]>([]);
  const [runs, setRuns] = useState<ApiRun[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [showCreate, setShowCreate] = useState(false);
  const [showImport, setShowImport] = useState(false);

  const fetchAll = async () => {
    try {
      setIsLoading(true);
      setError('');
      const [collectionsData, runsData] = await Promise.all([
        apiTestingApi.getCollections(),
        apiTestingApi.getAllRuns(),
      ]);
      setCollections(collectionsData);
      setRuns(runsData);
    } catch {
      setError('Failed to load API Testing data.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, []);

  const handleDuplicate = async (id: number, e: React.MouseEvent) => {
    e.preventDefault(); e.stopPropagation();
    try {
      const copy = await apiTestingApi.duplicateCollection(id);
      showToast(`Duplicated as "${copy.name}".`, 'success');
      fetchAll();
    } catch {
      showToast('Failed to duplicate collection.', 'error');
    }
  };

  const handleDelete = async (id: number, name: string, e: React.MouseEvent) => {
    e.preventDefault(); e.stopPropagation();
    if (!window.confirm(`Delete collection "${name}"? This removes every folder, request, and run history for it.`)) return;
    try {
      await apiTestingApi.deleteCollection(id);
      showToast(`Deleted "${name}".`, 'success');
      fetchAll();
    } catch {
      showToast('Failed to delete collection.', 'error');
    }
  };

  const totalRequestsPassed = runs.reduce((sum, r) => sum + r.passedRequests, 0);
  const totalRequestsRun = runs.reduce((sum, r) => sum + r.totalRequests, 0);

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'API Testing' }]}
        title="API Testing"
        subtitle={<p style={{ margin: 0 }}>Build, run, and monitor real HTTP API test suites — collections, environments, chaining, and assertions.</p>}
        actions={
          <>
            <button className="btn btn-secondary" onClick={() => navigate('/api-testing/environments')}>
              <Settings2 size={16} /> Environments
            </button>
            <button className="btn btn-secondary" onClick={() => setShowImport(true)}>
              <Upload size={16} /> Import
            </button>
            <button className="btn btn-secondary" onClick={() => setShowCreate(true)}>
              <FolderOpen size={16} /> New Collection
            </button>
            <button className="btn btn-primary" onClick={() => navigate('/api-testing/new')}>
              <Plus size={16} /> New Request
            </button>
          </>
        }
      />

      {error ? (
        <div className="card"><EmptyState title="Unable to load API Testing data" description={error} action={<button className="btn btn-primary" onClick={fetchAll}>Retry</button>} /></div>
      ) : isLoading ? (
        <>
          <div className="mb-6"><Skeleton variant="card" count={3} /></div>
          <Skeleton variant="row" count={4} />
        </>
      ) : collections.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '2.5rem 1.5rem' }}>
          <Send size={40} style={{ color: 'var(--text-subtle)', marginBottom: '1rem' }} />
          <h3 style={{ color: 'var(--text-main)', marginBottom: '0.5rem' }}>Create your first API test</h3>
          <p className="text-muted mx-auto mb-6" style={{ maxWidth: 420 }}>
            No setup required — you don't need a collection or environment to try your first request.
          </p>
          <div className="mx-auto mb-6 text-left" style={{ maxWidth: 340 }}>
            {[
              'Enter an API URL',
              'Choose the HTTP method',
              'Send the request',
              'Add an assertion',
              'Save it to a collection',
            ].map((step, i) => (
              <div key={step} className="flex items-center gap-3 mb-2">
                <span className="badge badge-neutral" style={{ minWidth: 22, textAlign: 'center' }}>{i + 1}</span>
                <span className="text-sm text-muted">{step}</span>
              </div>
            ))}
          </div>
          <div className="flex justify-center gap-3 flex-wrap">
            <button className="btn btn-primary" onClick={() => navigate('/api-testing/new')}><Plus size={16} /> New Request</button>
            <button className="btn btn-secondary" onClick={() => navigate('/api-testing/new', { state: { exampleSpec: exampleRequestSpec() } })}>
              <FlaskConical size={16} /> Try Example API
            </button>
          </div>
        </div>
      ) : (
        <>
          <div className="flex gap-4 mb-8 flex-wrap">
            <StatCard icon={<FolderOpen size={16} />} label="Collections" value={collections.length} />
            <StatCard icon={<Send size={16} />} label="Total Runs" value={runs.length} tone="primary" />
            <StatCard icon={<Send size={16} />} label="Requests Executed" value={totalRequestsRun} />
            <StatCard icon={<Send size={16} />} label="Requests Passed" value={totalRequestsPassed} tone={totalRequestsRun > 0 && totalRequestsPassed === totalRequestsRun ? 'success' : undefined} />
          </div>

          <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Collections</h3>
          <div className="flex flex-col gap-3 mb-8">
            {collections.map(c => (
              <Link key={c.id} to={`/api-testing/collections/${c.id}`} className="card no-underline" style={{ display: 'block' }}>
                <div className="flex items-center justify-between gap-3 flex-wrap">
                  <div style={{ minWidth: 0 }}>
                    <div className="font-medium" style={{ color: 'var(--text-main)' }}>{c.name}</div>
                    {c.description && <div className="text-sm text-muted truncate" style={{ maxWidth: 480 }}>{c.description}</div>}
                  </div>
                  <div className="flex items-center gap-2">
                    <button className="btn btn-secondary" style={{ padding: '0.3rem 0.6rem' }} title="Duplicate" onClick={e => handleDuplicate(c.id, e)}><Copy size={14} /></button>
                    <button className="btn btn-secondary" style={{ padding: '0.3rem 0.6rem' }} title="Delete" onClick={e => handleDelete(c.id, c.name, e)}><Trash2 size={14} /></button>
                  </div>
                </div>
              </Link>
            ))}
          </div>

          <h3 className="mb-4" style={{ fontSize: '0.95rem', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Recent Runs</h3>
          {runs.length === 0 ? (
            <div className="card"><EmptyState title="No runs yet" description="Open a collection and send a request, or run a full collection." /></div>
          ) : (
            <div className="table-wrapper">
              <table>
                <thead>
                  <tr>
                    <th>Run</th><th>Collection</th><th>Status</th><th>Requests</th><th>Duration</th><th>Date</th><th style={{ textAlign: 'right' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {runs.slice(0, 20).map(r => (
                    <tr key={r.id}>
                      <td className="font-medium">{r.runName}</td>
                      <td className="text-muted">{r.collection?.name ?? '—'}</td>
                      <td><StatusBadge status={r.status} size="sm" /></td>
                      <td>{r.passedRequests} / {r.totalRequests}</td>
                      <td>{(r.totalDurationMs / 1000).toFixed(1)}s</td>
                      <td className="text-muted">{new Date(r.startedAt).toLocaleString()}</td>
                      <td style={{ textAlign: 'right' }}>
                        <Link to={`/api-testing/runs/${r.id}`} className="btn btn-secondary no-underline" style={{ padding: '0.25rem 0.5rem' }}>View Report</Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      {showCreate && (
        <CreateCollectionDialog
          onClose={() => setShowCreate(false)}
          onCreated={c => { setShowCreate(false); navigate(`/api-testing/collections/${c.id}`); }}
        />
      )}
      {showImport && (
        <ImportDialog
          onClose={() => setShowImport(false)}
          onImported={c => { setShowImport(false); showToast(`Imported "${c.name}".`, 'success'); navigate(`/api-testing/collections/${c.id}`); }}
        />
      )}
    </div>
  );
}
