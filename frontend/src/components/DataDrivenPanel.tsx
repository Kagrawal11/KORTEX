import { useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { TestScenario, DatasetPreview, MappingValidationResult } from '../types';
import { dataDrivenApi } from '../services/uiAutomationApi';
import {
  Upload, Table2, Play, CheckCircle2, XCircle, AlertTriangle,
  ChevronDown, RefreshCw, Database, Check
} from 'lucide-react';

interface Props {
  test: TestScenario;
}

type PanelStep = 'upload' | 'configure' | 'mapping' | 'ready' | 'running';

const WIZARD_STAGES: { key: PanelStep; label: string }[] = [
  { key: 'upload', label: 'Upload' },
  { key: 'configure', label: 'Configure Loop' },
  { key: 'mapping', label: 'Map Fields' },
  { key: 'ready', label: 'Execute' },
];

// Mirrors FieldMappingService.looksLikeDropdownOptionSelector on the backend
// EXACTLY. These two checks must stay in sync: this one decides which steps
// the mapping UI offers, the backend's decides which steps validateMapping()/
// startRun() will actually accept. They previously diverged — this file used
// to also match any selector/label/placeholder merely CONTAINING the word
// "select", which the backend has since tightened away (that loose check
// misclassified ordinary buttons, e.g. one labelled "Select Plan", as
// mappable dropdown options). Offering a step here that the backend would
// then reject as "not an input step in the selected range" is exactly the
// failure mode keeping these in sync prevents.
//
// Deliberately does NOT match bare li[...]/li:has-text(...) or bare
// p-dropdown/p-multiselect — those match an ordinary FIXED-choice
// action-menu item (e.g. "Cancellation / Surrender", recorded with role="li")
// just as readily as a real dropdown OPTION (recorded with role="option").
// That was a real bug: a 2-column dataset got offered a 3rd, bogus mapping
// slot because a fixed menu item was misclassified as a mappable field. See
// isDropdownOptionStep below, which checks the recorded ARIA role FIRST —
// this selector-shape check is only the fallback for when that role wasn't
// captured.
//
// Also matches PrimeNG's real, unhyphenated custom element tags (e.g.
// <p-dropdownitem>, a component tag name, not a CSS class) — confirmed
// missing on a real recording where the backend's equivalent check only had
// the hyphenated class-name forms below. Covers the whole PrimeNG
// option-bearing widget family (Dropdown/MultiSelect/Listbox/SelectItem all
// follow the same `p-<widget>item` custom-tag convention).
function looksLikeDropdownOptionSelector(selector: string): boolean {
  const s = (selector || '').toLowerCase();
  return s.includes('mat-option') ||
    s.includes('dropdown-item') || s.includes('dropdownitem') ||
    s.includes('p-multiselect-item') || s.includes('multiselectitem') ||
    s.includes('selectitem') || s.includes('listboxitem') ||
    s.includes("role='option'") || s.includes('role="option"') ||
    s.includes('listbox') ||
    s.includes('option[') || s.startsWith('option:');
}

// Mirrors FieldMappingService.isInputStep()'s click-branch EXACTLY: the
// recorded ARIA role="option" is the strongest, most reliable signal that a
// click step is a genuine per-row-varying dropdown selection, not just an
// element that happens to look like one structurally.
function isDropdownOptionStep(s: { role?: string; primarySelector?: string }): boolean {
  if ((s.role || '').toLowerCase() === 'option') return true;
  return looksLikeDropdownOptionSelector(s.primarySelector || '');
}

// Mirrors FieldMappingService.findPrecedingDescriptiveText on the backend.
// A click-based dropdown OPTION step (the actual per-row selection, e.g. the
// cancellation reason clicked in a list) never has a labelText/name/id/
// placeholder of its own — only the sample value chosen during recording
// (e.g. "Borrower Deathh") — so without this, the row fell back to showing
// a raw CSS selector and was easy to mistake for missing entirely. The
// IMMEDIATELY PRECEDING step is very often the dropdown's own opening
// trigger (e.g. "Select Cancellation Reason").
//
// Only that ONE immediate predecessor is consulted — never a more distant
// earlier step. This used to walk back past any preceding step with blank
// recorded text to find the nearest one with SOME non-blank text, no matter
// how far back that was. Confirmed on a real recording that this actively
// misfires: a PrimeNG dropdown trigger whose visible label is empty until a
// value is chosen (so its own recorded text is blank) caused the lookup to
// walk straight past it to an unrelated, several-steps-earlier menu click
// and treat THAT text as this option's context — wrong context is worse
// than no context, since it can silently suppress a correct manual-mapping
// opportunity or produce a bogus auto-match. If the immediate predecessor's
// own text is blank, its labelText/ariaLabel/placeholder are tried instead
// (still anchored to that SAME step); if all of those are blank too, this
// returns undefined rather than guessing further.
function findPrecedingDescriptiveText(
  step: { id?: number; stepOrder: number },
  allStepsSorted: { id?: number; stepOrder: number; text?: string; labelText?: string; ariaLabel?: string; placeholder?: string }[]
): string | undefined {
  let immediatePredecessor: (typeof allStepsSorted)[number] | undefined;
  for (const candidate of allStepsSorted) {
    if (candidate.id !== undefined && candidate.id === step.id) continue;
    if (candidate.stepOrder >= step.stepOrder) continue;
    if (!immediatePredecessor || candidate.stepOrder > immediatePredecessor.stepOrder) {
      immediatePredecessor = candidate;
    }
  }
  if (!immediatePredecessor) return undefined;
  if (immediatePredecessor.text && immediatePredecessor.text.trim()) return immediatePredecessor.text.trim();
  if (immediatePredecessor.labelText && immediatePredecessor.labelText.trim()) return immediatePredecessor.labelText.trim();
  if (immediatePredecessor.ariaLabel && immediatePredecessor.ariaLabel.trim()) return immediatePredecessor.ariaLabel.trim();
  if (immediatePredecessor.placeholder && immediatePredecessor.placeholder.trim()) return immediatePredecessor.placeholder.trim();
  return undefined;
}

function buildFieldLabel(
  step: { labelText?: string; placeholder?: string; name?: string; elementId?: string; primarySelector: string; text?: string; stepOrder: number },
  contextualLabel: string | undefined
): string {
  if (step.labelText && step.labelText.trim()) return step.labelText.trim();
  if (step.placeholder && step.placeholder.trim()) return step.placeholder.trim();
  if (step.name && step.name.trim()) return step.name.trim();
  if (step.elementId && step.elementId.trim()) return step.elementId.trim();
  const hasContext = !!(contextualLabel && contextualLabel.trim());
  const hasSample = !!(step.text && step.text.trim());
  if (hasContext && hasSample) return `${contextualLabel} (e.g. ${step.text!.trim()})`;
  if (hasContext) return contextualLabel!.trim();
  if (hasSample) return step.text!.trim();
  return `Step ${step.stepOrder}`;
}

// Mirrors FieldMappingService.normaliseHeader on the backend EXACTLY.
function normaliseHeader(s: string): string {
  return (s || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

// Mirrors FieldMappingService.isColumnDrivenCandidate on the backend.
//
// isDropdownOptionStep's role/selector-shape checks can miss a real dropdown
// option entirely — confirmed on two real recordings of the SAME logical
// click ("select the Borrower Death reason"): one captured role="option",
// the other — depending on exactly which DOM element inside the option
// received the click — captured a bare <span> with no ARIA role and no
// recognised selector shape at all, indistinguishable from a genuinely
// fixed menu click by structure alone.
//
// The dataset's own column names are a much stronger, low-risk signal: if
// this step's nearest PRECEDING step's text matches a column name the user
// has already told us about (e.g. "Select Cancellation Reason"), this step
// is almost certainly that column's per-row selection.
function isColumnDrivenCandidate(
  step: { id?: number; stepOrder: number; actionType: string; role?: string; primarySelector: string; inputValue?: string },
  allStepsSorted: { id?: number; stepOrder: number; text?: string; labelText?: string; ariaLabel?: string; placeholder?: string }[],
  datasetHeaders: string[]
): boolean {
  if ((step.actionType || '').toLowerCase() !== 'click') return false;
  // Already covered by the normal path — must not be double-processed.
  if (step.inputValue && step.inputValue.trim() !== '') return false;
  if (isDropdownOptionStep(step)) return false;
  if (!datasetHeaders || datasetHeaders.length === 0) return false;

  const contextualLabel = findPrecedingDescriptiveText(step, allStepsSorted);
  if (!contextualLabel) return false;

  const normalisedContext = normaliseHeader(contextualLabel);
  return datasetHeaders.some(h =>
    contextualLabel === (h || '').trim() || normalisedContext === normaliseHeader(h)
  );
}

// Mirrors FieldMappingService.valueMatchedColumn on the backend.
//
// The most direct, robust signal available: does this step's OWN recorded
// sample text (e.g. "Borrower Deathh") match an actual cell VALUE somewhere
// in the dataset? Unlike isColumnDrivenCandidate, this needs nothing from
// any OTHER step — confirmed necessary on a real recording where even the
// dropdown's own opening trigger was captured with blank text (the click
// landed on an element with empty innerText, e.g. an icon within it).
function valueMatchedColumn(
  step: { text?: string },
  sampleRows: Record<string, string>[] | undefined,
  datasetHeaders: string[]
): string | undefined {
  if (!step.text || !step.text.trim()) return undefined;
  if (!sampleRows || sampleRows.length === 0) return undefined;
  if (!datasetHeaders || datasetHeaders.length === 0) return undefined;

  const needle = step.text.trim();
  const needleNormalised = normaliseHeader(needle);

  for (const header of datasetHeaders) {
    for (const row of sampleRows) {
      const cell = row?.[header];
      if (!cell || !cell.trim()) continue;
      const cellTrimmed = cell.trim();
      if (cellTrimmed.toLowerCase() === needle.toLowerCase() || normaliseHeader(cellTrimmed) === needleNormalised) {
        return header;
      }
    }
  }
  return undefined;
}

export default function DataDrivenPanel({ test }: Props) {
  const navigate   = useNavigate();
  const fileRef    = useRef<HTMLInputElement>(null);

  // State
  const [panelStep, setPanelStep]           = useState<PanelStep>('upload');
  const [file, setFile]                     = useState<File | null>(null);
  const [preview, setPreview]               = useState<DatasetPreview | null>(null);
  const [startStep, setStartStep]           = useState<number | ''>('');
  const [endStep, setEndStep]               = useState<number | ''>('');
  const [mappingResult, setMappingResult]   = useState<MappingValidationResult | null>(null);
  const [manualMappings, setManualMappings] = useState<Record<string, string>>({});
  const [error, setError]                   = useState('');
  const [uploading, setUploading]           = useState(false);
  const [validating, setValidating]         = useState(false);
  const [starting, setStarting]             = useState(false);

  const steps = test.steps ? [...test.steps].sort((a, b) => a.stepOrder - b.stepOrder) : [];
  const inputSteps = steps.filter(s => {
    const a = (s.actionType || '').toLowerCase();
    // Text-input actions
    if (a === 'input' || a === 'change' || a === 'type' || a === 'fill') return true;
    // Click steps that represent dropdown option selections:
    // — they have a recorded inputValue (the option text), OR
    // — their selector structurally looks like a dropdown/listbox option
    if (a === 'click') {
      if (s.inputValue && s.inputValue.trim() !== '') return true;
      if (isDropdownOptionStep(s)) return true;
      // Neither signal fired — this is exactly the case a real recording
      // exposed: the option was captured as a bare <span> with no ARIA
      // role and no recognised selector shape at all. Fall back to two
      // dataset-anchored signals instead of guessing from structure:
      // (a) the step immediately preceding this one reads like a column
      //     NAME (e.g. "Select Cancellation Reason"), or
      // (b) this step's OWN recorded sample text matches an actual cell
      //     VALUE somewhere in the dataset (e.g. "Borrower Deathh") — this
      //     one needs nothing from any other step, which a second real
      //     recording showed matters: even the dropdown's own trigger can
      //     be captured with blank text.
      if (!preview) return false;
      if (isColumnDrivenCandidate(s, steps, preview.headers)) return true;
      return !!valueMatchedColumn(s, preview.previewRows, preview.headers);
    }
    return false;
  });

  // ── File upload ────────────────────────────────────────────────────────
  const handleFileDrop = (e: React.DragEvent) => {
    e.preventDefault();
    const f = e.dataTransfer.files[0];
    if (f) handleFile(f);
  };

  const handleFileInput = (e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0];
    if (f) handleFile(f);
  };

  const handleFile = async (f: File) => {
    const lower = f.name.toLowerCase();
    if (!lower.endsWith('.csv') && !lower.endsWith('.xlsx')) {
      setError('Unsupported file type. Only .csv and .xlsx are accepted.');
      return;
    }
    setError('');
    setFile(f);
    setPreview(null);
    setMappingResult(null);
    setUploading(true);
    try {
      const p = await dataDrivenApi.previewDataset(test.id, f);
      setPreview(p);
      setPanelStep('configure');
    } catch (err: any) {
      setError(err?.response?.data?.error || err?.message || 'Failed to parse file.');
    } finally {
      setUploading(false);
    }
  };

  // ── Validate mapping ───────────────────────────────────────────────────
  const handleValidate = async () => {
    if (!preview || startStep === '' || endStep === '') return;
    if (Number(startStep) > Number(endStep)) {
      setError('Start step cannot be after end step.');
      return;
    }
    setError('');
    setValidating(true);
    try {
      const result = await dataDrivenApi.validateMapping(
        test.id,
        Number(startStep),
        Number(endStep),
        preview.headers,
        Object.keys(manualMappings).length > 0 ? manualMappings : undefined,
        preview.previewRows
      );
      setMappingResult(result);
      setPanelStep(result.valid ? 'ready' : 'mapping');
    } catch (err: any) {
      setError(err?.response?.data?.error || err?.message || 'Mapping validation failed.');
    } finally {
      setValidating(false);
    }
  };

  // ── Start run ──────────────────────────────────────────────────────────
  const handleStartRun = async () => {
    if (!file || !mappingResult || !mappingResult.valid) return;
    setError('');
    setStarting(true);
    setPanelStep('running');
    try {
      const config = {
        startStepOrder: Number(startStep),
        endStepOrder: Number(endStep),
        resolvedMappings: mappingResult.resolvedMappings,
        manualMappings: Object.keys(manualMappings).length > 0 ? manualMappings : undefined,
      };
      const run = await dataDrivenApi.startRun(test.id, file, config);
      navigate(`/ui-automation/data-driven/runs/${run.id}`);
    } catch (err: any) {
      setError(err?.response?.data?.error || err?.message || 'Failed to start run.');
      setStarting(false);
    }
  };

  // ── Reset ──────────────────────────────────────────────────────────────
  const handleReset = () => {
    setFile(null);
    setPreview(null);
    setStartStep('');
    setEndStep('');
    setMappingResult(null);
    setManualMappings({});
    setError('');
    setPanelStep('upload');
    if (fileRef.current) fileRef.current.value = '';
  };

  // ── Manual mapping change ─────────────────────────────────────────────
  const handleManualMappingChange = (stepId: number, column: string) => {
    setManualMappings(prev => ({ ...prev, [String(stepId)]: column }));
    setMappingResult(null); // need to re-validate
  };

  const stepLabel = (order: number) => {
    const s = steps.find(st => st.stepOrder === order);
    if (!s) return `Step ${order}`;
    const label = s.labelText || s.placeholder || s.name || s.elementId || s.primarySelector || '';
    return `Step ${order}${label ? ' — ' + label.substring(0, 40) : ''}`;
  };

  // ─────────────────────────────────────────────────────────────────────

  const cardStyle: React.CSSProperties = {
    background: 'var(--bg-card)',
    border: '1px solid var(--border-color)',
    borderRadius: 'var(--radius-lg)',
    padding: '1.5rem',
  };

  const sectionHeaderStyle: React.CSSProperties = {
    display: 'flex',
    alignItems: 'center',
    gap: '0.5rem',
    marginBottom: '1rem',
    fontSize: '0.95rem',
    fontWeight: 600,
    color: 'var(--text-main)',
  };

  const labelStyle: React.CSSProperties = {
    fontSize: '0.8rem',
    color: 'var(--text-muted)',
    marginBottom: '0.35rem',
    display: 'block',
  };

  const selectStyle: React.CSSProperties = {
    background: 'var(--bg-input)',
    border: '1px solid var(--border-color)',
    borderRadius: 'var(--radius-md)',
    color: 'var(--text-main)',
    padding: '0.45rem 0.75rem',
    width: '100%',
    fontSize: '0.875rem',
  };

  if (steps.length === 0) {
    return (
      <div style={{ ...cardStyle, color: 'var(--text-muted)', textAlign: 'center', padding: '2rem' }}>
        No recorded steps available. Record the test first before configuring data-driven execution.
      </div>
    );
  }

  // Purely visual — reflects the existing panelStep state (already updated at
  // each real transition: upload success, validate success/failure, run start).
  // 'running' shares the "Execute" stage's position since it's a sub-state of it.
  const wizardStageIndex = WIZARD_STAGES.findIndex(s => s.key === (panelStep === 'running' ? 'ready' : panelStep));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.25rem' }}>

      {/* ── Wizard stage indicator ── */}
      <div className="card flex items-center" style={{ padding: '1.1rem 1.5rem' }}>
        {WIZARD_STAGES.map((stage, i) => (
          <div key={stage.key} className="flex items-center" style={{ flex: i < WIZARD_STAGES.length - 1 ? 1 : undefined }}>
            <div className="flex items-center gap-2">
              <span style={{
                width: 26, height: 26, borderRadius: '50%', flexShrink: 0,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                fontSize: '0.7rem', fontWeight: 700,
                background: i < wizardStageIndex ? 'var(--success)' : i === wizardStageIndex ? 'var(--gradient-primary)' : 'var(--bg-elevated)',
                boxShadow: i === wizardStageIndex ? 'var(--glow-primary)' : 'none',
                color: i <= wizardStageIndex ? 'white' : 'var(--text-subtle)',
                transition: 'all var(--transition-base)',
              }}>
                {i < wizardStageIndex ? <Check size={13} /> : i + 1}
              </span>
              <span className="text-sm" style={{ color: i === wizardStageIndex ? 'var(--text-main)' : 'var(--text-subtle)', fontWeight: i === wizardStageIndex ? 600 : 400, whiteSpace: 'nowrap' }}>
                {stage.label}
              </span>
            </div>
            {i < WIZARD_STAGES.length - 1 && (
              <div style={{ flex: 1, height: 2, margin: '0 0.75rem', background: i < wizardStageIndex ? 'var(--success)' : 'var(--border-color)', transition: 'background var(--transition-base)' }} />
            )}
          </div>
        ))}
      </div>

      {/* ── STEP 1: File Upload ── */}
      <div style={cardStyle}>
        <div style={sectionHeaderStyle}>
          <Upload size={16} style={{ color: 'var(--primary)' }} />
          Test Data
          {preview && (
            <span style={{
              marginLeft: 'auto', fontSize: '0.8rem', color: 'var(--success)',
              display: 'flex', alignItems: 'center', gap: '4px'
            }}>
              <CheckCircle2 size={14} /> {file?.name}
            </span>
          )}
        </div>

        {!preview ? (
          <div className="flex gap-4 flex-wrap">
            <div
              onDrop={handleFileDrop}
              onDragOver={e => e.preventDefault()}
              onClick={() => fileRef.current?.click()}
              style={{
                flex: '2 1 320px',
                border: '2px dashed var(--border-color)',
                borderRadius: 'var(--radius-md)',
                padding: '2.5rem 1rem',
                textAlign: 'center',
                cursor: 'pointer',
                transition: 'border-color 0.2s',
              }}
              onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--primary)')}
              onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--border-color)')}
            >
              <input ref={fileRef} type="file" accept=".csv,.xlsx" onChange={handleFileInput} style={{ display: 'none' }} />
              {uploading ? (
                <div style={{ color: 'var(--text-muted)', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '8px' }}>
                  <RefreshCw size={18} style={{ animation: 'spin 1s linear infinite' }} /> Parsing file…
                </div>
              ) : (
                <>
                  <Upload size={28} style={{ color: 'var(--primary)', marginBottom: '0.75rem' }} />
                  <p style={{ marginBottom: '0.5rem', color: 'var(--text-main)' }}>
                    <strong>Drop CSV or XLSX here</strong> or click to browse
                  </p>
                  <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', margin: 0 }}>
                    Supports .csv and .xlsx files
                  </p>
                </>
              )}
            </div>

            {/* Illustrative only — clearly labeled so it can never be mistaken
                for the user's actual data or a real requirement (any headers
                are accepted; the loop step range determines what gets mapped). */}
            <div style={{ flex: '1 1 260px', border: '1px solid var(--border-color)', borderRadius: 'var(--radius-md)', padding: '1rem', background: 'var(--bg-input)' }}>
              <div className="text-xs text-muted mb-2" style={{ textTransform: 'uppercase', letterSpacing: '0.04em' }}>Example format (illustrative)</div>
              <div style={{ fontSize: '0.78rem', overflowX: 'auto' }}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead>
                    <tr style={{ color: 'var(--text-subtle)' }}>
                      <th style={{ textAlign: 'left', padding: '0.2rem 0.4rem', fontWeight: 600 }}>username</th>
                      <th style={{ textAlign: 'left', padding: '0.2rem 0.4rem', fontWeight: 600 }}>password</th>
                    </tr>
                  </thead>
                  <tbody className="text-muted">
                    <tr><td style={{ padding: '0.2rem 0.4rem' }}>user1</td><td style={{ padding: '0.2rem 0.4rem' }}>pass1</td></tr>
                    <tr><td style={{ padding: '0.2rem 0.4rem' }}>user2</td><td style={{ padding: '0.2rem 0.4rem' }}>pass2</td></tr>
                  </tbody>
                </table>
              </div>
              <p className="text-xs text-subtle" style={{ marginTop: '0.6rem' }}>
                Any column headers work — you'll map them to recorded steps next.
              </p>
            </div>
          </div>
        ) : (
          <div>
            <div style={{
              display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '1rem'
            }}>
              {[
                { label: 'File', value: file?.name || '' },
                { label: 'Rows detected', value: String(preview.rowCount) },
                { label: 'Columns detected', value: String(preview.columnCount) },
              ].map(({ label, value }) => (
                <div key={label} style={{
                  flex: '1 1 140px', background: 'var(--bg-input)',
                  borderRadius: 'var(--radius-md)', padding: '0.75rem 1rem',
                  border: '1px solid var(--border-color)'
                }}>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>{label}</div>
                  <div style={{ fontWeight: 600, fontSize: '0.9rem', wordBreak: 'break-all' }}>{value}</div>
                </div>
              ))}
            </div>

            {/* Column header chips */}
            <div style={{ marginBottom: '0.75rem' }}>
              <span style={labelStyle}>Columns: </span>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.4rem' }}>
                {preview.headers.map(h => (
                  <span key={h} style={{
                    background: 'rgba(59,130,246,0.12)', color: 'var(--primary)',
                    border: '1px solid rgba(59,130,246,0.3)', borderRadius: '999px',
                    padding: '2px 10px', fontSize: '0.78rem'
                  }}>{h}</span>
                ))}
              </div>
            </div>

            <button
              onClick={handleReset}
              style={{
                background: 'none', border: '1px solid var(--border-color)',
                color: 'var(--text-muted)', borderRadius: 'var(--radius-md)',
                padding: '0.35rem 0.9rem', cursor: 'pointer', fontSize: '0.8rem'
              }}
            >
              Change file
            </button>
          </div>
        )}
      </div>

      {/* ── STEP 2: Loop Configuration ── */}
      {preview && (
        <div style={cardStyle}>
          <div style={sectionHeaderStyle}>
            <Table2 size={16} style={{ color: 'var(--primary)' }} />
            Data Loop — Step Range
          </div>

          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap' }}>
            <div style={{ flex: '1 1 220px' }}>
              <label style={labelStyle}>Start Step (loop begins here)</label>
              <div style={{ position: 'relative' }}>
                <select
                  value={startStep}
                  onChange={e => { setStartStep(Number(e.target.value)); setMappingResult(null); }}
                  style={selectStyle}
                >
                  <option value="">Select start step…</option>
                  {steps.map(s => (
                    <option key={s.id || s.stepOrder} value={s.stepOrder}>
                      {stepLabel(s.stepOrder)}
                    </option>
                  ))}
                </select>
                <ChevronDown size={14} style={{
                  position: 'absolute', right: '10px', top: '50%',
                  transform: 'translateY(-50%)', pointerEvents: 'none',
                  color: 'var(--text-muted)'
                }} />
              </div>
            </div>

            <div style={{ flex: '1 1 220px' }}>
              <label style={labelStyle}>End Step (loop ends here, inclusive)</label>
              <div style={{ position: 'relative' }}>
                <select
                  value={endStep}
                  onChange={e => { setEndStep(Number(e.target.value)); setMappingResult(null); }}
                  style={selectStyle}
                >
                  <option value="">Select end step…</option>
                  {steps.map(s => (
                    <option
                      key={s.id || s.stepOrder}
                      value={s.stepOrder}
                      disabled={startStep !== '' && s.stepOrder < Number(startStep)}
                    >
                      {stepLabel(s.stepOrder)}
                    </option>
                  ))}
                </select>
                <ChevronDown size={14} style={{
                  position: 'absolute', right: '10px', top: '50%',
                  transform: 'translateY(-50%)', pointerEvents: 'none',
                  color: 'var(--text-muted)'
                }} />
              </div>
            </div>
          </div>

          {startStep !== '' && endStep !== '' && Number(startStep) <= Number(endStep) && (
            <div style={{
              marginTop: '1rem', padding: '0.75rem 1rem',
              background: 'rgba(59,130,246,0.08)', borderRadius: 'var(--radius-md)',
              border: '1px solid rgba(59,130,246,0.25)', fontSize: '0.85rem',
              color: 'var(--primary)'
            }}>
              <strong>Loop:</strong>{' '}
              Steps {startStep} → {endStep} will repeat <strong>{preview.rowCount}</strong> times (once per data row).
              Steps before {startStep} run once as pre-loop. Steps after {endStep} run once as post-loop.
            </div>
          )}
        </div>
      )}

      {/* ── STEP 3: Field Mapping ── */}
      {preview && startStep !== '' && endStep !== '' && Number(startStep) <= Number(endStep) && (
        <div style={cardStyle}>
          <div style={sectionHeaderStyle}>
            <Database size={16} style={{ color: 'var(--primary)' }} />
            Field Mapping
            {mappingResult?.valid && (
              <span style={{
                marginLeft: 'auto', fontSize: '0.8rem', color: 'var(--success)',
                display: 'flex', alignItems: 'center', gap: '4px'
              }}>
                <CheckCircle2 size={14} /> Mapping Valid
              </span>
            )}
          </div>

          {/* Input steps in range */}
          {inputSteps.filter(s => s.stepOrder >= Number(startStep) && s.stepOrder <= Number(endStep)).length === 0 ? (
            <div style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>
              No input steps found in the selected range. The loop will execute the steps without data substitution.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
              {inputSteps
                .filter(s => s.stepOrder >= Number(startStep) && s.stepOrder <= Number(endStep))
                .map(step => {
                  const resolved = mappingResult?.resolvedMappings?.find(m => m.stepId === step.id);
                  const unresolved = mappingResult && !resolved;
                  const fieldLabel = buildFieldLabel(step, findPrecedingDescriptiveText(step, steps));
                  return (
                    <div key={step.id || step.stepOrder} style={{
                      display: 'flex', alignItems: 'center', gap: '1rem',
                      padding: '0.6rem 0.9rem',
                      background: 'var(--bg-input)',
                      borderRadius: 'var(--radius-md)',
                      border: `1px solid ${unresolved && mappingResult ? 'var(--error)' : 'var(--border-color)'}`,
                      flexWrap: 'wrap',
                    }}>
                      <div style={{ flex: '1 1 160px', fontSize: '0.875rem' }}>
                        <div style={{ fontWeight: 500 }}>{fieldLabel}</div>
                        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Step {step.stepOrder}</div>
                      </div>
                      <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>→</div>
                      <div style={{ flex: '1 1 180px' }}>
                        <select
                          value={manualMappings[String(step.id)] || resolved?.datasetColumn || ''}
                          onChange={e => handleManualMappingChange(step.id!, e.target.value)}
                          style={{ ...selectStyle, width: '100%' }}
                        >
                          <option value="">— auto-detect —</option>
                          {preview.headers.map(h => (
                            <option key={h} value={h}>{h}</option>
                          ))}
                        </select>
                      </div>
                      <div style={{ width: '20px' }}>
                        {resolved && <CheckCircle2 size={18} style={{ color: 'var(--success)' }} />}
                        {unresolved && mappingResult && <XCircle size={18} style={{ color: 'var(--error)' }} />}
                      </div>
                    </div>
                  );
                })}
            </div>
          )}

          {mappingResult && !mappingResult.valid && mappingResult.unresolvedFields.length > 0 && (
            <div style={{
              marginTop: '0.75rem', padding: '0.75rem', borderRadius: 'var(--radius-md)',
              background: 'var(--error-bg)', color: 'var(--error)', fontSize: '0.875rem'
            }}>
              <strong>Mapping failed:</strong> Could not map these fields automatically:
              <ul style={{ margin: '0.35rem 0 0 1rem', padding: 0 }}>
                {mappingResult.unresolvedFields.map(f => <li key={f}>{f}</li>)}
              </ul>
              Please select the correct column for each field above and validate again.
            </div>
          )}

          <div style={{ marginTop: '1rem' }}>
            <button
              className="btn btn-secondary"
              onClick={handleValidate}
              disabled={validating}
              style={{ display: 'flex', alignItems: 'center', gap: '6px' }}
            >
              {validating
                ? <><RefreshCw size={15} style={{ animation: 'spin 1s linear infinite' }} /> Validating…</>
                : <><CheckCircle2 size={15} /> Validate Mapping</>}
            </button>
          </div>
        </div>
      )}

      {/* ── STEP 4: Execution ── */}
      {mappingResult?.valid && preview && (
        <div style={cardStyle}>
          <div style={sectionHeaderStyle}>
            <Play size={16} style={{ color: 'var(--success)' }} />
            Execution
          </div>

          <div style={{ display: 'flex', gap: '1rem', flexWrap: 'wrap', marginBottom: '1.25rem' }}>
            {[
              { label: 'Total Rows', value: preview.rowCount, color: 'var(--primary)' },
              { label: 'Start Step', value: startStep, color: 'var(--text-main)' },
              { label: 'End Step', value: endStep, color: 'var(--text-main)' },
            ].map(({ label, value, color }) => (
              <div key={label} style={{
                flex: '1 1 120px', background: 'var(--bg-input)',
                borderRadius: 'var(--radius-md)', padding: '0.75rem 1rem',
                border: '1px solid var(--border-color)', textAlign: 'center'
              }}>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>{label}</div>
                <div style={{ fontWeight: 700, fontSize: '1.1rem', color }}>{String(value)}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
            <button
              className="btn btn-primary"
              onClick={handleStartRun}
              disabled={starting}
              style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '0.6rem 1.5rem' }}
            >
              {starting
                ? <><RefreshCw size={16} style={{ animation: 'spin 1s linear infinite' }} /> Starting…</>
                : <><Play size={16} /> START DATA-DRIVEN RUN</>}
            </button>
            <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              {preview.rowCount} iteration{preview.rowCount !== 1 ? 's' : ''} will execute sequentially
            </span>
          </div>
        </div>
      )}

      {/* Error display */}
      {error && (
        <div style={{
          padding: '0.85rem 1rem', borderRadius: 'var(--radius-md)',
          background: 'var(--error-bg)', color: 'var(--error)',
          border: '1px solid var(--error)', fontSize: '0.875rem',
          display: 'flex', alignItems: 'flex-start', gap: '8px'
        }}>
          <AlertTriangle size={16} style={{ flexShrink: 0, marginTop: '2px' }} />
          {error}
        </div>
      )}
    </div>
  );
}
