import { useState } from 'react';
import { uiAutomationApi } from '../services/uiAutomationApi';
import { useNavigate } from 'react-router-dom';

interface CreateTestDialogProps {
  onClose: () => void;
}

export default function CreateTestDialog({ onClose }: CreateTestDialogProps) {
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');
  const navigate = useNavigate();

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
      // Navigate to recording workspace
      navigate(`/ui-automation/recording/${newTest.id}`);
    } catch (err: any) {
      setError(err.response?.data?.message || 'Failed to create test. Please try again.');
      setIsSubmitting(false);
    }
  };

  return (
    <div style={overlayStyle}>
      <div className="card" style={dialogStyle}>
        <div className="flex items-center justify-between mb-4">
          <h2>Create UI Automation Test</h2>
          <button className="btn btn-secondary" onClick={onClose} style={{ padding: '0.25rem 0.5rem' }}>✕</button>
        </div>
        <p className="mb-6">Configure your test before starting the recording.</p>

        {error && (
          <div className="mb-4" style={{ padding: '0.75rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)' }}>
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit}>
          <div className="mb-4">
            <label className="label">Project</label>
            <select className="input" disabled>
              <option>Demo App (Default)</option>
            </select>
          </div>

          <div className="mb-4">
            <label className="label">Test Name</label>
            <input 
              type="text" 
              className="input" 
              placeholder="e.g. Login Flow Validation" 
              value={name}
              onChange={(e) => setName(e.target.value)}
              disabled={isSubmitting}
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

          <div className="flex items-center justify-between mb-6" style={{ padding: '1rem', border: '1px solid var(--border-color)', borderRadius: 'var(--radius-md)', backgroundColor: 'rgba(59, 130, 246, 0.05)' }}>
            <div>
              <h4 style={{ marginBottom: '0.25rem' }}>Record & Play</h4>
              <p style={{ fontSize: '0.875rem' }}>Interact with your website manually and automatically capture your actions.</p>
            </div>
            <input type="radio" checked readOnly style={{ width: '1.25rem', height: '1.25rem', accentColor: 'var(--primary)' }} />
          </div>

          <div className="flex justify-between items-center mt-6 pt-4 border-t border-[var(--border-color)]">
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

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  backgroundColor: 'rgba(0,0,0,0.7)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000,
  backdropFilter: 'blur(4px)',
};

const dialogStyle: React.CSSProperties = {
  width: '100%',
  maxWidth: '500px',
  maxHeight: '90vh',
  overflowY: 'auto',
  backgroundColor: 'var(--bg-panel)',
};
