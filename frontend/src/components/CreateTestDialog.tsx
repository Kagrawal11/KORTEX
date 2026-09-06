import { useState } from 'react';
import { uiAutomationApi } from '../services/uiAutomationApi';
import { useNavigate } from 'react-router-dom';
import { MousePointerClick, X } from 'lucide-react';
import { useToast } from './Toast';

interface CreateTestDialogProps {
  onClose: () => void;
}

export default function CreateTestDialog({ onClose }: CreateTestDialogProps) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();
  const { showToast } = useToast();

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !url.trim()) {
      setError('Name and URL are required.');
      return;
    }

    let targetUrl = url.trim();
    if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
      targetUrl = 'https://' + targetUrl;
    }

    try {
      setIsSubmitting(true);
      setError('');
      const newTest = await uiAutomationApi.createTest({
        name: name.trim(),
        targetUrl: targetUrl,
      });
      showToast(`Test "${newTest.name}" created — ready to record.`, 'success');
      // Navigate to recording workspace
      navigate(`/ui-automation/recording/${newTest.id}`);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create test. Please try again.');
      setIsSubmitting(false);
    }
  };

  return (
    <div className="modal-overlay">
      <div className="card modal-panel">
        <div className="flex items-center justify-between mb-4">
          <h2>Create UI Automation Test</h2>
          <button className="btn-ghost" onClick={onClose} aria-label="Close">
            <X size={18} />
          </button>
        </div>
        <p className="mb-6">Configure your test before starting the recording.</p>

        {error && (
          <div className="mb-4" style={{ padding: '0.75rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)' }}>
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="mb-4">
            <label className="label">Test Name</label>
            <input
              type="text"
              className="input"
              placeholder="e.g. Login Flow Validation"
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={isSubmitting}
              autoFocus
            />
          </div>

          <div className="mb-6">
            <label className="label">Website URL</label>
            <input
              type="text"
              className="input"
              placeholder="e.g. https://example.com"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
              disabled={isSubmitting}
            />
          </div>

          <div className="flex items-center gap-4 mb-6" style={{ padding: '1rem', border: '1px solid var(--border-color)', borderRadius: 'var(--radius-md)', backgroundColor: 'var(--primary-bg)' }}>
            <span className="icon-badge accent-blue" style={{ width: 36, height: 36 }}><MousePointerClick size={18} /></span>
            <div>
              <h4 className="mb-1">Record &amp; Play</h4>
              <p style={{ fontSize: '0.875rem', margin: 0 }}>Interact with your website manually and automatically capture your actions.</p>
            </div>
          </div>

          <div className="flex justify-between items-center mt-6 pt-4 border-t">
            <button type="button" className="btn btn-secondary" onClick={onClose} disabled={isSubmitting}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={isSubmitting}>
              {isSubmitting ? 'Creating test...' : 'Create & Start Recording'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
