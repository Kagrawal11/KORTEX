import { useMemo, useState } from 'react';
import { ClipboardCheck, Save, ChevronDown, ChevronUp } from 'lucide-react';
import { MANUAL_ACCESSIBILITY_CHECKLIST } from '../data/manualAccessibilityChecklist';
import type { ManualCheckStatus, ManualChecksMap } from '../types';
import { accessibilityApi } from '../services/accessibilityApi';
import { useToast } from './Toast';

interface ManualChecklistProps {
  runId: number;
  initialChecks: ManualChecksMap;
}

const STATUS_OPTIONS: { value: ManualCheckStatus; label: string }[] = [
  { value: 'NOT_CHECKED', label: 'Not checked' },
  { value: 'PASS', label: 'Pass' },
  { value: 'FAIL', label: 'Fail' },
  { value: 'NOT_APPLICABLE', label: 'N/A' },
];

function statusBadgeClass(status: ManualCheckStatus): string {
  switch (status) {
    case 'PASS': return 'badge-success';
    case 'FAIL': return 'badge-error';
    case 'NOT_APPLICABLE': return 'badge-neutral';
    default: return 'badge-neutral';
  }
}

export default function ManualChecklist({ runId, initialChecks }: ManualChecklistProps) {
  const { showToast } = useToast();
  const [collapsed, setCollapsed] = useState(true);
  const [checks, setChecks] = useState<ManualChecksMap>(initialChecks);
  const [isSaving, setIsSaving] = useState(false);

  const summary = useMemo(() => {
    let checked = 0;
    let failed = 0;
    for (const item of MANUAL_ACCESSIBILITY_CHECKLIST) {
      const status = checks[item.id]?.status;
      if (status && status !== 'NOT_CHECKED') checked += 1;
      if (status === 'FAIL') failed += 1;
    }
    return { checked, failed, total: MANUAL_ACCESSIBILITY_CHECKLIST.length };
  }, [checks]);

  const updateStatus = (id: string, status: ManualCheckStatus) => {
    setChecks(prev => ({ ...prev, [id]: { status, notes: prev[id]?.notes ?? '' } }));
  };

  const updateNotes = (id: string, notes: string) => {
    setChecks(prev => ({ ...prev, [id]: { status: prev[id]?.status ?? 'NOT_CHECKED', notes } }));
  };

  const handleSave = async () => {
    try {
      setIsSaving(true);
      await accessibilityApi.saveManualChecks(runId, checks);
      showToast('Manual checklist saved.', 'success');
    } catch {
      showToast('Failed to save the manual checklist.', 'error');
    } finally {
      setIsSaving(false);
    }
  };

  return (
    <div className="card mb-8">
      <button
        onClick={() => setCollapsed(v => !v)}
        className="flex items-center justify-between gap-3 flex-wrap"
        style={{ width: '100%', textAlign: 'left', background: 'none', border: 'none', cursor: 'pointer', padding: 0, font: 'inherit', color: 'inherit' }}
      >
        <div className="flex items-center gap-2">
          <ClipboardCheck size={16} />
          <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Manual & Guided Testing Checklist</h3>
          <span className="badge badge-neutral">{summary.checked} / {summary.total} checked</span>
          {summary.failed > 0 && <span className="badge badge-error">{summary.failed} failed</span>}
        </div>
        {collapsed ? <ChevronDown size={16} /> : <ChevronUp size={16} />}
      </button>

      {!collapsed && (
        <>
          <p className="text-sm text-muted mt-4" style={{ marginBottom: '1rem' }}>
            Automated scans cannot verify these — they require a human tester. Answers are saved per run and are not part of the automated score above.
          </p>

          <div className="flex flex-col gap-3">
            {MANUAL_ACCESSIBILITY_CHECKLIST.map(item => {
              const entry = checks[item.id];
              const status = entry?.status ?? 'NOT_CHECKED';
              return (
                <div key={item.id} style={{ background: 'var(--bg-input)', borderRadius: 'var(--radius-md)', padding: '0.85rem 1rem' }}>
                  <div className="flex items-center justify-between gap-3 flex-wrap mb-2">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`badge ${statusBadgeClass(status)}`}>{STATUS_OPTIONS.find(o => o.value === status)?.label}</span>
                      <span className="font-medium">{item.label}</span>
                      <span className="text-xs text-subtle">WCAG {item.wcagRef}</span>
                    </div>
                    <select
                      className="select"
                      style={{ width: 'auto', minWidth: 130 }}
                      value={status}
                      onChange={e => updateStatus(item.id, e.target.value as ManualCheckStatus)}
                    >
                      {STATUS_OPTIONS.map(opt => <option key={opt.value} value={opt.value}>{opt.label}</option>)}
                    </select>
                  </div>
                  <p className="text-sm text-muted" style={{ margin: '0 0 0.5rem' }}>{item.description}</p>
                  <textarea
                    className="input"
                    placeholder="Notes (optional) — what you tested and what you found…"
                    rows={2}
                    style={{ width: '100%', resize: 'vertical', fontFamily: 'inherit' }}
                    value={entry?.notes ?? ''}
                    onChange={e => updateNotes(item.id, e.target.value)}
                  />
                </div>
              );
            })}
          </div>

          <div className="flex justify-end mt-4">
            <button className="btn btn-primary" onClick={handleSave} disabled={isSaving}>
              <Save size={16} /> {isSaving ? 'Saving…' : 'Save Checklist'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
