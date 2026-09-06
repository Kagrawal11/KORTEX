import { useState } from 'react';
import { Mail, X, Send, CheckCircle2, AlertTriangle, RefreshCw } from 'lucide-react';
import { reportEmailApi } from '../services/reportEmailApi';
import type { EmailReportType } from '../types';

interface EmailReportDialogProps {
  reportType: EmailReportType;
  runId: number;
  onClose: () => void;
}

// Lightweight client-side format check only, for fast feedback — the
// backend's InternetAddress-based validation is the authoritative check.
const EMAIL_FORMAT = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

function parseRecipients(raw: string): string[] {
  return Array.from(new Set(raw.split(/[,;\n]/).map(s => s.trim()).filter(Boolean)));
}

export default function EmailReportDialog({ reportType, runId, onClose }: EmailReportDialogProps) {
  const [emailsInput, setEmailsInput] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState('');
  const [successMessage, setSuccessMessage] = useState('');

  const recipients = parseRecipients(emailsInput);
  const invalidEntry = recipients.find(r => !EMAIL_FORMAT.test(r));

  const handleSend = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isSending || successMessage) return; // prevent duplicate submissions
    setError('');

    if (recipients.length === 0) {
      setError('Please enter at least one recipient email address.');
      return;
    }
    if (invalidEntry) {
      setError(`"${invalidEntry}" doesn't look like a valid email address.`);
      return;
    }

    try {
      setIsSending(true);
      const response = await reportEmailApi.emailReport({ reportType, runId, recipients });
      setSuccessMessage(response.message);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to send the report. Please try again.');
    } finally {
      setIsSending(false);
    }
  };

  return (
    <div className="modal-overlay" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="card modal-panel" style={{ maxWidth: 440 }}>
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-2">
            <span className="icon-badge accent-blue" style={{ width: 34, height: 34 }}><Mail size={16} /></span>
            <h2 style={{ margin: 0, fontSize: '1.1rem' }}>Email Report</h2>
          </div>
          <button className="btn-ghost" onClick={onClose} aria-label="Close"><X size={18} /></button>
        </div>

        {successMessage ? (
          <div className="flex flex-col items-center text-center" style={{ padding: '1rem 0' }}>
            <CheckCircle2 size={36} className="text-success" style={{ marginBottom: '0.75rem' }} />
            <p style={{ margin: 0, color: 'var(--text-main)' }}>{successMessage}</p>
            <button className="btn btn-primary mt-6" onClick={onClose}>Done</button>
          </div>
        ) : (
          <form onSubmit={handleSend}>
            <p className="text-sm mb-4">Send this execution report as a PDF attachment to one or more recipients.</p>

            {error && (
              <div className="flex items-start gap-2 mb-4" style={{ padding: '0.65rem 0.85rem', background: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)', fontSize: '0.85rem' }}>
                <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: 2 }} />
                <span>{error}</span>
              </div>
            )}

            <label className="label">Recipient email address(es)</label>
            <textarea
              className="input"
              style={{ minHeight: 72, resize: 'vertical', fontFamily: 'inherit' }}
              placeholder="e.g. qa-lead@company.com, manager@company.com"
              value={emailsInput}
              onChange={e => setEmailsInput(e.target.value)}
              disabled={isSending}
              autoFocus
            />
            <p className="text-xs text-subtle mt-1" style={{ marginTop: '0.35rem' }}>Separate multiple addresses with commas.</p>

            <div className="flex justify-end gap-2 mt-6" style={{ marginTop: '1.5rem' }}>
              <button type="button" className="btn btn-secondary" onClick={onClose} disabled={isSending}>Cancel</button>
              <button type="submit" className="btn btn-primary" disabled={isSending || recipients.length === 0}>
                {isSending ? <><RefreshCw size={15} className="animate-spin" /> Sending…</> : <><Send size={15} /> Send Report</>}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
