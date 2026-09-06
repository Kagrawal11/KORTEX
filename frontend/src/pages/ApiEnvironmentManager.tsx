import { useEffect, useState } from 'react';
import { Plus, Trash2, Save, Eye, EyeOff, X } from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import type { ApiEnvironment, ApiEnvironmentVariable } from '../types';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

function parseVariables(json: string): ApiEnvironmentVariable[] {
  try { return JSON.parse(json); } catch { return []; }
}

export default function ApiEnvironmentManager() {
  const { showToast } = useToast();
  const [environments, setEnvironments] = useState<ApiEnvironment[]>([]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [variables, setVariables] = useState<ApiEnvironmentVariable[]>([]);
  const [revealed, setRevealed] = useState<Set<number>>(new Set());
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isCreatingNew, setIsCreatingNew] = useState(false);

  const fetchAll = async (selectAfterId?: number) => {
    try {
      setIsLoading(true);
      const data = await apiTestingApi.getEnvironments();
      setEnvironments(data);
      const target = selectAfterId ?? data[0]?.id ?? null;
      if (target != null) selectEnvironment(data.find(e => e.id === target) ?? data[0]);
      else { setSelectedId(null); setName(''); setVariables([]); }
    } catch {
      showToast('Failed to load environments.', 'error');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => { fetchAll(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);

  const selectEnvironment = (env: ApiEnvironment | undefined) => {
    if (!env) return;
    setSelectedId(env.id);
    setName(env.name);
    setVariables(parseVariables(env.variablesJson));
    setIsCreatingNew(false);
    setRevealed(new Set());
  };

  const startNew = () => {
    setSelectedId(null);
    setName('New Environment');
    setVariables([]);
    setIsCreatingNew(true);
  };

  const rowsWithTrailing = variables.length === 0 || variables[variables.length - 1].key !== ''
    ? [...variables, { key: '', value: '', secret: false }] : variables;

  const updateRow = (index: number, patch: Partial<ApiEnvironmentVariable>) => {
    const next = rowsWithTrailing.map((row, i) => (i === index ? { ...row, ...patch } : row));
    setVariables(next.filter((row, i) => i === next.length - 1 || row.key.trim() !== ''));
  };

  const removeRow = (index: number) => {
    setVariables(rowsWithTrailing.filter((_, i) => i !== index));
  };

  const toggleReveal = (index: number) => {
    setRevealed(prev => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index); else next.add(index);
      return next;
    });
  };

  const handleSave = async () => {
    if (!name.trim()) { showToast('Environment name is required.', 'error'); return; }
    const cleanVars = variables.filter(v => v.key.trim() !== '');
    try {
      setIsSaving(true);
      if (isCreatingNew || selectedId == null) {
        const created = await apiTestingApi.createEnvironment(name.trim(), cleanVars);
        showToast(`Environment "${created.name}" created.`, 'success');
        await fetchAll(created.id);
      } else {
        const updated = await apiTestingApi.updateEnvironment(selectedId, name.trim(), cleanVars);
        showToast(`Environment "${updated.name}" saved.`, 'success');
        await fetchAll(updated.id);
      }
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to save environment.', 'error');
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async () => {
    if (selectedId == null) return;
    if (!window.confirm(`Delete environment "${name}"?`)) return;
    try {
      await apiTestingApi.deleteEnvironment(selectedId);
      showToast('Environment deleted.', 'success');
      await fetchAll();
    } catch {
      showToast('Failed to delete environment.', 'error');
    }
  };

  if (isLoading) {
    return (
      <div className="container">
        <Skeleton variant="text" width="40%" height={32} />
        <div className="mt-6"><Skeleton variant="card" count={3} /></div>
      </div>
    );
  }

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'API Testing', to: '/api-testing' }, { label: 'Environments' }]}
        title="Environments"
        subtitle={<p style={{ margin: 0 }}>Reusable variable sets — Local, QA, Staging, Production — for requests to run against.</p>}
        actions={<button className="btn btn-primary" onClick={startNew}><Plus size={16} /> New Environment</button>}
      />

      <div className="flex gap-6" style={{ alignItems: 'flex-start' }}>
        <div className="card" style={{ width: 240, flexShrink: 0, padding: '0.5rem' }}>
          {environments.length === 0 && !isCreatingNew ? (
            <div style={{ padding: '1rem' }}><EmptyState title="No environments" description="Create one to get started." /></div>
          ) : (
            environments.map(env => (
              <button
                key={env.id}
                onClick={() => selectEnvironment(env)}
                className="api-tree-item"
                style={{ width: '100%', textAlign: 'left', border: 'none', background: selectedId === env.id && !isCreatingNew ? 'var(--primary-bg)' : 'none', color: selectedId === env.id && !isCreatingNew ? 'var(--primary)' : 'var(--text-muted)', font: 'inherit' }}
              >
                {env.name}
              </button>
            ))
          )}
        </div>

        {(selectedId != null || isCreatingNew) && (
          <div className="card" style={{ flex: 1 }}>
            <div className="flex items-center justify-between gap-3 mb-6 flex-wrap">
              <input className="input" style={{ maxWidth: 320, fontWeight: 600 }} value={name} onChange={e => setName(e.target.value)} placeholder="Environment name" />
              <div className="flex gap-2">
                {!isCreatingNew && (
                  <button className="btn btn-secondary" onClick={handleDelete}><Trash2 size={14} /> Delete</button>
                )}
                <button className="btn btn-primary" onClick={handleSave} disabled={isSaving}><Save size={14} /> {isSaving ? 'Saving…' : 'Save'}</button>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-2 text-xs text-subtle" style={{ padding: '0 0.4rem' }}>
                <span style={{ width: 20 }} />
                <span style={{ flex: '1 1 35%' }}>KEY</span>
                <span style={{ flex: '1 1 45%' }}>VALUE</span>
                <span style={{ width: 60 }}>SECRET</span>
                <span style={{ width: 32 }} />
              </div>
              {rowsWithTrailing.map((row, i) => (
                <div key={i} className="flex items-center gap-2">
                  <span style={{ width: 20, textAlign: 'center', color: 'var(--text-subtle)' }}>{'{{}}'}</span>
                  <input className="input" style={{ flex: '1 1 35%', fontFamily: 'var(--font-mono)' }} placeholder="key" value={row.key} onChange={e => updateRow(i, { key: e.target.value })} />
                  <input
                    className="input"
                    style={{ flex: '1 1 45%', fontFamily: 'var(--font-mono)' }}
                    placeholder="value"
                    type={row.secret && !revealed.has(i) ? 'password' : 'text'}
                    value={row.value}
                    onChange={e => updateRow(i, { value: e.target.value })}
                  />
                  <label style={{ width: 60, display: 'flex', alignItems: 'center', gap: 4 }} title="Mask this value in history, reports, and logs">
                    <input type="checkbox" checked={row.secret} onChange={e => updateRow(i, { secret: e.target.checked })} />
                  </label>
                  <button className="btn-ghost" style={{ width: 32 }} onClick={() => toggleReveal(i)} title={revealed.has(i) ? 'Hide' : 'Reveal'} disabled={!row.secret}>
                    {revealed.has(i) ? <EyeOff size={14} /> : <Eye size={14} />}
                  </button>
                  <button className="btn-ghost" style={{ width: 32 }} onClick={() => removeRow(i)} title="Remove" disabled={i === rowsWithTrailing.length - 1 && row.key === ''}>
                    <X size={14} />
                  </button>
                </div>
              ))}
            </div>
            <p className="text-xs text-subtle mt-4" style={{ margin: '1rem 0 0' }}>
              Use these anywhere as <code className="font-mono">{'{{key}}'}</code>. Secret values are masked in execution history, reports, and error messages.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
