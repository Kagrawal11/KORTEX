import { useEffect, useState } from 'react';
import { X, Save } from 'lucide-react';
import { apiTestingApi } from '../services/apiTestingApi';
import type { ApiCollection, ApiFolder, ApiRequestSpec } from '../types';
import { useToast } from './Toast';

interface SaveRequestDialogProps {
  spec: ApiRequestSpec;
  onClose: () => void;
  onSaved: (requestId: number, collectionId: number) => void;
}

/**
 * "Save Request" — the bridge between the no-setup Playground and the
 * organised Collection/Folder structure. A first-time user never has to
 * create a collection before experimenting; this is the ONE moment they're
 * asked where a request should live, and creating a brand-new collection
 * right here (no separate trip to the dashboard) is the default path.
 */
export default function SaveRequestDialog({ spec, onClose, onSaved }: SaveRequestDialogProps) {
  const { showToast } = useToast();
  const [requestName, setRequestName] = useState(spec.name || 'New Request');
  const [collections, setCollections] = useState<ApiCollection[]>([]);
  const [mode, setMode] = useState<'existing' | 'new'>('new');
  const [selectedCollectionId, setSelectedCollectionId] = useState<number | null>(null);
  const [newCollectionName, setNewCollectionName] = useState('My API Tests');
  const [folders, setFolders] = useState<ApiFolder[]>([]);
  const [selectedFolderId, setSelectedFolderId] = useState<number | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    apiTestingApi.getCollections().then(cols => {
      setCollections(cols);
      if (cols.length > 0) {
        setMode('existing');
        setSelectedCollectionId(cols[0].id);
      }
      setIsLoading(false);
    }).catch(() => setIsLoading(false));
  }, []);

  useEffect(() => {
    if (mode === 'existing' && selectedCollectionId != null) {
      apiTestingApi.getFolders(selectedCollectionId).then(setFolders).catch(() => setFolders([]));
      setSelectedFolderId(null);
    } else {
      setFolders([]);
    }
  }, [mode, selectedCollectionId]);

  const handleSave = async () => {
    if (!requestName.trim()) { showToast('Request name is required.', 'error'); return; }
    if (mode === 'new' && !newCollectionName.trim()) { showToast('Collection name is required.', 'error'); return; }
    if (mode === 'existing' && selectedCollectionId == null) { showToast('Choose a collection.', 'error'); return; }

    try {
      setIsSaving(true);
      const collectionId = mode === 'new'
        ? (await apiTestingApi.createCollection(newCollectionName.trim())).id
        : selectedCollectionId!;
      const created = await apiTestingApi.createRequest(collectionId, mode === 'existing' ? selectedFolderId : null, { ...spec, name: requestName.trim() });
      showToast(`Saved "${created.name}".`, 'success');
      onSaved(created.id, collectionId);
    } catch (err: any) {
      showToast(err?.response?.data?.error || 'Failed to save the request.', 'error');
      setIsSaving(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="card modal-panel">
        <div className="flex items-center justify-between mb-4">
          <h2>Save Request</h2>
          <button className="btn-ghost" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>
        <p className="mb-6">A collection is just a group of saved API requests — pick one, or create a new one.</p>

        <div className="mb-4">
          <label className="label">Request Name</label>
          <input className="input" value={requestName} onChange={e => setRequestName(e.target.value)} autoFocus />
        </div>

        {!isLoading && (
          <>
            <div className="flex gap-2 mb-4">
              {collections.length > 0 && (
                <button className={`btn ${mode === 'existing' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setMode('existing')}>Existing Collection</button>
              )}
              <button className={`btn ${mode === 'new' ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setMode('new')}>New Collection</button>
            </div>

            {mode === 'existing' ? (
              <>
                <div className="mb-4">
                  <label className="label">Collection</label>
                  <select className="select" value={selectedCollectionId ?? ''} onChange={e => setSelectedCollectionId(Number(e.target.value))}>
                    {collections.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                  </select>
                </div>
                {folders.length > 0 && (
                  <div className="mb-6">
                    <label className="label">Folder (optional)</label>
                    <select className="select" value={selectedFolderId ?? ''} onChange={e => setSelectedFolderId(e.target.value ? Number(e.target.value) : null)}>
                      <option value="">No folder</option>
                      {folders.map(f => <option key={f.id} value={f.id}>{f.name}</option>)}
                    </select>
                  </div>
                )}
              </>
            ) : (
              <div className="mb-6">
                <label className="label">New Collection Name</label>
                <input className="input" value={newCollectionName} onChange={e => setNewCollectionName(e.target.value)} placeholder="e.g. My API Tests" />
              </div>
            )}
          </>
        )}

        <div className="flex justify-between items-center mt-6 pt-4 border-t">
          <button className="btn btn-secondary" onClick={onClose} disabled={isSaving}>Cancel</button>
          <button className="btn btn-primary" onClick={handleSave} disabled={isSaving || isLoading}>
            <Save size={14} /> {isSaving ? 'Saving…' : 'Save Request'}
          </button>
        </div>
      </div>
    </div>
  );
}
