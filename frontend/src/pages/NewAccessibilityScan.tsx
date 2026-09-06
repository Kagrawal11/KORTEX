import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { AlertTriangle, Globe, ScanSearch } from 'lucide-react';
import { accessibilityApi } from '../services/accessibilityApi';
import type { AccessibilityScanScope, AccessibilityStandard } from '../types';
import PageHeader from '../components/PageHeader';
import { useToast } from '../components/Toast';

const STANDARD_OPTIONS: { key: AccessibilityStandard; label: string; description: string }[] = [
  { key: 'WCAG_A', label: 'WCAG A', description: 'Baseline accessibility requirements.' },
  { key: 'WCAG_AA', label: 'WCAG AA', description: 'The level most legal/compliance standards require.' },
  { key: 'BEST_PRACTICES', label: 'Best Practices', description: "Additional checks axe-core recommends beyond WCAG itself." },
];

function looksLikeUnsupportedProtocol(value: string): boolean {
  const match = value.trim().match(/^([a-zA-Z][a-zA-Z0-9+.-]*):\/\//);
  return !!match && !['http', 'https'].includes(match[1].toLowerCase());
}

export default function NewAccessibilityScan() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [targetUrl, setTargetUrl] = useState('');
  const [scanScope, setScanScope] = useState<AccessibilityScanScope>('FULL_PAGE');
  const [selector, setSelector] = useState('');
  const [standards, setStandards] = useState<Set<AccessibilityStandard>>(
    () => new Set(['WCAG_A', 'WCAG_AA', 'BEST_PRACTICES'])
  );
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  const toggleStandard = (key: AccessibilityStandard) => {
    setStandards(prev => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');

    if (!name.trim()) {
      setError('Scan name is required.');
      return;
    }
    if (!targetUrl.trim()) {
      setError('Please enter a valid HTTP/HTTPS URL.');
      return;
    }
    if (looksLikeUnsupportedProtocol(targetUrl)) {
      setError('Please enter a valid HTTP/HTTPS URL — only http:// and https:// are supported.');
      return;
    }
    if (scanScope === 'SELECTOR' && !selector.trim()) {
      setError('A CSS selector is required when scanning a specific selector.');
      return;
    }

    let normalizedUrl = targetUrl.trim();
    if (!/^https?:\/\//i.test(normalizedUrl)) {
      normalizedUrl = 'https://' + normalizedUrl;
    }

    try {
      setIsSubmitting(true);
      const run = await accessibilityApi.createScan({
        name: name.trim(),
        description: description.trim() || undefined,
        targetUrl: normalizedUrl,
        scanScope,
        selector: scanScope === 'SELECTOR' ? selector.trim() : undefined,
        standards: Array.from(standards),
      });
      showToast(`"${name.trim()}" scan started.`, 'success');
      navigate(`/accessibility/runs/${run.id}`);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to start the scan. Please try again.');
      setIsSubmitting(false);
    }
  };

  return (
    <div className="container" style={{ maxWidth: 760 }}>
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Accessibility', to: '/accessibility' }, { label: 'New Scan' }]}
        title="New Accessibility Scan"
        subtitle={<p style={{ margin: 0 }}>Configure a target and run a real axe-core accessibility analysis against it.</p>}
      />

      {error && (
        <div className="card mb-6" style={{ backgroundColor: 'var(--error-bg)', borderColor: 'var(--error)', color: 'var(--error)', display: 'flex', alignItems: 'flex-start', gap: '12px' }}>
          <AlertTriangle size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span>{error}</span>
        </div>
      )}

      <form onSubmit={handleSubmit}>
        <div className="card mb-6">
          <div className="flex items-center gap-2 mb-4">
            <Globe size={16} className="text-primary" />
            <h3 style={{ margin: 0, fontSize: '1rem' }}>Target</h3>
          </div>

          <div className="mb-4">
            <label className="label">Scan Name</label>
            <input
              className="input"
              placeholder="e.g. Homepage Accessibility Check"
              value={name}
              onChange={e => setName(e.target.value)}
              disabled={isSubmitting}
              autoFocus
            />
          </div>

          <div className="mb-4">
            <label className="label">Target URL</label>
            <input
              className="input"
              placeholder="e.g. https://example.com"
              value={targetUrl}
              onChange={e => setTargetUrl(e.target.value)}
              disabled={isSubmitting}
            />
          </div>

          <div>
            <label className="label">Description (optional)</label>
            <input
              className="input"
              placeholder="What is this scan checking?"
              value={description}
              onChange={e => setDescription(e.target.value)}
              disabled={isSubmitting}
            />
          </div>
        </div>

        <div className="card mb-6">
          <h3 className="mb-4" style={{ fontSize: '1rem' }}>Scan Scope</h3>
          <div className="flex gap-4 flex-wrap mb-4">
            {(['FULL_PAGE', 'SELECTOR'] as AccessibilityScanScope[]).map(scope => (
              <label
                key={scope}
                className="flex items-center gap-3"
                style={{
                  flex: '1 1 240px', padding: '0.85rem 1rem', borderRadius: 'var(--radius-md)',
                  border: `1px solid ${scanScope === scope ? 'var(--primary)' : 'var(--border-color)'}`,
                  background: scanScope === scope ? 'var(--primary-bg)' : 'transparent', cursor: 'pointer',
                }}
              >
                <input type="radio" name="scanScope" checked={scanScope === scope} onChange={() => setScanScope(scope)} disabled={isSubmitting} />
                <div>
                  <div className="font-medium" style={{ color: 'var(--text-main)' }}>{scope === 'FULL_PAGE' ? 'Full Page' : 'Specific Selector'}</div>
                  <div className="text-xs text-muted">{scope === 'FULL_PAGE' ? 'Scan the entire rendered page.' : 'Scope the scan to one CSS selector.'}</div>
                </div>
              </label>
            ))}
          </div>
          {scanScope === 'SELECTOR' && (
            <div>
              <label className="label">CSS Selector</label>
              <input
                className="input font-mono"
                placeholder="e.g. #main-content"
                value={selector}
                onChange={e => setSelector(e.target.value)}
                disabled={isSubmitting}
              />
            </div>
          )}
        </div>

        <div className="card mb-6">
          <h3 className="mb-1" style={{ fontSize: '1rem' }}>Standards</h3>
          <p className="text-sm mb-4" style={{ margin: 0, marginBottom: '1rem' }}>Which axe-core rule sets to check. A sensible WCAG configuration is selected by default.</p>
          <div className="flex flex-col gap-3">
            {STANDARD_OPTIONS.map(opt => (
              <label key={opt.key} className="flex items-center gap-3" style={{ cursor: 'pointer' }}>
                <input type="checkbox" checked={standards.has(opt.key)} onChange={() => toggleStandard(opt.key)} disabled={isSubmitting} />
                <div>
                  <div className="font-medium" style={{ color: 'var(--text-main)' }}>{opt.label}</div>
                  <div className="text-xs text-muted">{opt.description}</div>
                </div>
              </label>
            ))}
          </div>
        </div>

        <div className="card mb-6" style={{ display: 'flex', alignItems: 'flex-start', gap: '12px', background: 'var(--info-bg)', borderColor: 'var(--primary)', color: 'var(--primary)' }}>
          <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: '2px' }} />
          <span className="text-sm">Automated scanning finds many common issues, but not every accessibility problem — manual review is still needed for full coverage.</span>
        </div>

        <div className="flex justify-end">
          <button type="submit" className="btn btn-primary" disabled={isSubmitting} style={{ padding: '0.65rem 1.5rem' }}>
            <ScanSearch size={17} />
            {isSubmitting ? 'Starting scan...' : 'Start Accessibility Scan'}
          </button>
        </div>
      </form>
    </div>
  );
}
