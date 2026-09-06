import { useState } from 'react';
import { Trash2, Wand2, SlidersHorizontal, KeyRound, Rows3, Braces, CheckCircle2, ArrowRightLeft, Wand } from 'lucide-react';
import type {
  ApiRequestSpec, ApiAuthType, ApiBodyType, AssertionDefinition, AssertionType,
  ExtractionRule, ExtractionSource, ExtractionSaveTo, PreRequestVariable,
} from '../types';
import KeyValueEditor from './KeyValueEditor';

type EditorTab = 'params' | 'auth' | 'headers' | 'body' | 'preRequest' | 'assertions' | 'extraction';

const ASSERTION_TYPES: { value: AssertionType; label: string; needsTarget: boolean; needsExpected: boolean; expectedPlaceholder?: string }[] = [
  { value: 'STATUS_CODE_EQUALS', label: 'Status code equals', needsTarget: false, needsExpected: true, expectedPlaceholder: '200' },
  { value: 'STATUS_CODE_NOT_EQUALS', label: 'Status code not equals', needsTarget: false, needsExpected: true, expectedPlaceholder: '500' },
  { value: 'STATUS_CODE_ONE_OF', label: 'Status code one of', needsTarget: false, needsExpected: true, expectedPlaceholder: '200,201,204' },
  { value: 'RESPONSE_TIME_LESS_THAN', label: 'Response time less than (ms)', needsTarget: false, needsExpected: true, expectedPlaceholder: '2000' },
  { value: 'RESPONSE_TIME_GREATER_THAN', label: 'Response time greater than (ms)', needsTarget: false, needsExpected: true, expectedPlaceholder: '0' },
  { value: 'HEADER_EXISTS', label: 'Header exists', needsTarget: true, needsExpected: false },
  { value: 'HEADER_EQUALS', label: 'Header equals', needsTarget: true, needsExpected: true },
  { value: 'HEADER_CONTAINS', label: 'Header contains', needsTarget: true, needsExpected: true },
  { value: 'BODY_CONTAINS', label: 'Body contains', needsTarget: false, needsExpected: true },
  { value: 'BODY_EQUALS', label: 'Body equals', needsTarget: false, needsExpected: true },
  { value: 'JSON_PATH_EXISTS', label: 'JSON path exists', needsTarget: true, needsExpected: false },
  { value: 'JSON_PATH_EQUALS', label: 'JSON path equals', needsTarget: true, needsExpected: true },
  { value: 'JSON_PATH_NOT_EQUALS', label: 'JSON path not equals', needsTarget: true, needsExpected: true },
  { value: 'JSON_PATH_CONTAINS', label: 'JSON path contains', needsTarget: true, needsExpected: true },
];

interface RequestEditorTabsProps {
  spec: ApiRequestSpec;
  onChange: (spec: ApiRequestSpec) => void;
  collectionAuthType?: ApiAuthType;
}

export default function RequestEditorTabs({ spec, onChange, collectionAuthType }: RequestEditorTabsProps) {
  const [tab, setTab] = useState<EditorTab>('params');
  const enabledCount = (n: number) => (n > 0 ? <span className="badge-count">{n}</span> : null);

  const set = <K extends keyof ApiRequestSpec>(key: K, value: ApiRequestSpec[K]) => onChange({ ...spec, [key]: value });

  return (
    <div>
      <div className="tabs-bar" style={{ marginBottom: 0 }}>
        <button className={`tab-btn ${tab === 'params' ? 'active' : ''}`} onClick={() => setTab('params')}><SlidersHorizontal size={13} /> Params {enabledCount(spec.params.filter(p => p.key).length)}</button>
        <button className={`tab-btn ${tab === 'auth' ? 'active' : ''}`} onClick={() => setTab('auth')}><KeyRound size={13} /> Authorization</button>
        <button className={`tab-btn ${tab === 'headers' ? 'active' : ''}`} onClick={() => setTab('headers')}><Rows3 size={13} /> Headers {enabledCount(spec.headers.filter(h => h.key).length)}</button>
        <button className={`tab-btn ${tab === 'body' ? 'active' : ''}`} onClick={() => setTab('body')}><Braces size={13} /> Body{spec.bodyType !== 'NONE' ? ' •' : ''}</button>
        <button className={`tab-btn ${tab === 'assertions' ? 'active' : ''}`} onClick={() => setTab('assertions')}><CheckCircle2 size={13} /> Add Assertion {enabledCount(spec.assertions.length)}</button>
        <button className={`tab-btn ${tab === 'extraction' ? 'active' : ''}`} onClick={() => setTab('extraction')}><ArrowRightLeft size={13} /> Extract Value {enabledCount(spec.extractions.length)}</button>
        <button className={`tab-btn ${tab === 'preRequest' ? 'active' : ''}`} onClick={() => setTab('preRequest')}><Wand size={13} /> Pre-request {enabledCount(spec.preRequestVars.length)}</button>
      </div>

      <div style={{ padding: '1rem' }}>
        {tab === 'params' && (
          <KeyValueEditor items={spec.params} onChange={items => set('params', items)} keyPlaceholder="Param name" valuePlaceholder="Value" />
        )}

        {tab === 'auth' && (
          <AuthEditor spec={spec} onChange={onChange} collectionAuthType={collectionAuthType} />
        )}

        {tab === 'headers' && (
          <KeyValueEditor items={spec.headers} onChange={items => set('headers', items)} keyPlaceholder="Header name" valuePlaceholder="Value" />
        )}

        {tab === 'body' && (
          <BodyEditor spec={spec} onChange={onChange} />
        )}

        {tab === 'preRequest' && (
          <PreRequestEditor variables={spec.preRequestVars} onChange={v => set('preRequestVars', v)} />
        )}

        {tab === 'assertions' && (
          <AssertionsEditor assertions={spec.assertions} onChange={v => set('assertions', v)} />
        )}

        {tab === 'extraction' && (
          <ExtractionEditor rules={spec.extractions} onChange={v => set('extractions', v)} />
        )}
      </div>
    </div>
  );
}

// ── Auth ─────────────────────────────────────────────────────────────────

function AuthEditor({ spec, onChange, collectionAuthType }: RequestEditorTabsProps) {
  const setAuthType = (type: ApiAuthType) => onChange({ ...spec, authType: type, auth: { ...spec.auth, type } });
  const setAuthField = (patch: Partial<typeof spec.auth>) => onChange({ ...spec, auth: { ...spec.auth, ...patch } });

  return (
    <div className="flex flex-col gap-4" style={{ maxWidth: 480 }}>
      <div>
        <label className="label">Type</label>
        <select className="select" value={spec.authType} onChange={e => setAuthType(e.target.value as ApiAuthType)}>
          <option value="INHERIT">Inherit from collection{collectionAuthType && collectionAuthType !== 'NONE' ? ` (${collectionAuthType})` : ' (None)'}</option>
          <option value="NONE">None</option>
          <option value="BEARER">Bearer Token</option>
          <option value="BASIC">Basic Auth</option>
          <option value="API_KEY">API Key</option>
          <option value="CUSTOM_HEADER">Custom Header</option>
        </select>
      </div>

      {spec.authType === 'BEARER' && (
        <div>
          <label className="label">Token</label>
          <input className="input" style={{ fontFamily: 'var(--font-mono)' }} placeholder="{{token}}" value={spec.auth.token ?? ''} onChange={e => setAuthField({ token: e.target.value })} />
        </div>
      )}

      {spec.authType === 'BASIC' && (
        <>
          <div><label className="label">Username</label><input className="input" value={spec.auth.username ?? ''} onChange={e => setAuthField({ username: e.target.value })} /></div>
          <div><label className="label">Password</label><input className="input" type="password" value={spec.auth.password ?? ''} onChange={e => setAuthField({ password: e.target.value })} /></div>
        </>
      )}

      {spec.authType === 'API_KEY' && (
        <>
          <div><label className="label">Key Name</label><input className="input" style={{ fontFamily: 'var(--font-mono)' }} placeholder="X-Api-Key" value={spec.auth.apiKeyName ?? ''} onChange={e => setAuthField({ apiKeyName: e.target.value })} /></div>
          <div><label className="label">Key Value</label><input className="input" style={{ fontFamily: 'var(--font-mono)' }} placeholder="{{apiKey}}" value={spec.auth.apiKeyValue ?? ''} onChange={e => setAuthField({ apiKeyValue: e.target.value })} /></div>
          <div>
            <label className="label">Add To</label>
            <select className="select" value={spec.auth.apiKeyAddTo ?? 'HEADER'} onChange={e => setAuthField({ apiKeyAddTo: e.target.value as 'HEADER' | 'QUERY' })}>
              <option value="HEADER">Header</option>
              <option value="QUERY">Query Params</option>
            </select>
          </div>
        </>
      )}

      {spec.authType === 'CUSTOM_HEADER' && (
        <>
          <div><label className="label">Header Name</label><input className="input" style={{ fontFamily: 'var(--font-mono)' }} value={spec.auth.customHeaderName ?? ''} onChange={e => setAuthField({ customHeaderName: e.target.value })} /></div>
          <div><label className="label">Header Value</label><input className="input" style={{ fontFamily: 'var(--font-mono)' }} value={spec.auth.customHeaderValue ?? ''} onChange={e => setAuthField({ customHeaderValue: e.target.value })} /></div>
        </>
      )}

      {(spec.authType === 'NONE' || spec.authType === 'INHERIT') && (
        <p className="text-sm text-muted">
          {spec.authType === 'NONE' ? 'No authentication will be sent with this request.' : 'This request uses the collection’s default authentication.'}
        </p>
      )}
    </div>
  );
}

// ── Body ─────────────────────────────────────────────────────────────────

function BodyEditor({ spec, onChange }: RequestEditorTabsProps) {
  const setBodyType = (bodyType: ApiBodyType) => onChange({ ...spec, bodyType });
  const [jsonError, setJsonError] = useState('');

  const formatJson = () => {
    try {
      const formatted = JSON.stringify(JSON.parse(spec.bodyContent), null, 2);
      onChange({ ...spec, bodyContent: formatted });
      setJsonError('');
    } catch {
      setJsonError('Cannot format — the body is not valid JSON.');
    }
  };

  const handleBodyContentChange = (value: string) => {
    onChange({ ...spec, bodyContent: value });
    if (spec.bodyType === 'JSON') {
      if (!value.trim()) { setJsonError(''); return; }
      try { JSON.parse(value); setJsonError(''); } catch { setJsonError('This is not valid JSON.'); }
    }
  };

  return (
    <div>
      <div className="flex items-center gap-4 mb-3">
        {(['NONE', 'JSON', 'RAW', 'FORM_URLENCODED', 'MULTIPART'] as ApiBodyType[]).map(type => (
          <label key={type} className="flex items-center gap-1" style={{ fontSize: '0.85rem', cursor: 'pointer' }}>
            <input type="radio" name="bodyType" checked={spec.bodyType === type} onChange={() => setBodyType(type)} />
            {type === 'FORM_URLENCODED' ? 'Form URL Encoded' : type === 'MULTIPART' ? 'Multipart Form' : type.charAt(0) + type.slice(1).toLowerCase()}
          </label>
        ))}
      </div>

      {(spec.bodyType === 'JSON' || spec.bodyType === 'RAW') && (
        <div>
          {spec.bodyType === 'JSON' && (
            <div className="flex justify-between items-center mb-2">
              {jsonError ? <span className="text-xs" style={{ color: 'var(--error)' }}>{jsonError}</span> : <span />}
              <button className="btn btn-secondary" style={{ padding: '0.3rem 0.6rem' }} onClick={formatJson}><Wand2 size={13} /> Format</button>
            </div>
          )}
          <textarea
            className="input"
            style={{ width: '100%', minHeight: 180, fontFamily: 'var(--font-mono)', fontSize: '0.8rem', resize: 'vertical' }}
            placeholder={spec.bodyType === 'JSON' ? '{\n  "key": "value"\n}' : 'Request body…'}
            value={spec.bodyContent}
            onChange={e => handleBodyContentChange(e.target.value)}
          />
        </div>
      )}

      {(spec.bodyType === 'FORM_URLENCODED' || spec.bodyType === 'MULTIPART') && (
        <KeyValueEditor items={spec.formFields} onChange={items => onChange({ ...spec, formFields: items })} />
      )}

      {spec.bodyType === 'NONE' && <p className="text-sm text-muted">This request has no body.</p>}
    </div>
  );
}

// ── Pre-request variables ────────────────────────────────────────────────

function PreRequestEditor({ variables, onChange }: { variables: PreRequestVariable[]; onChange: (v: PreRequestVariable[]) => void }) {
  const rows = variables.length === 0 || variables[variables.length - 1].name !== '' ? [...variables, { name: '', value: '' }] : variables;

  const updateRow = (i: number, patch: Partial<PreRequestVariable>) => {
    const next = rows.map((r, idx) => (idx === i ? { ...r, ...patch } : r));
    onChange(next.filter((r, idx) => idx === next.length - 1 || r.name.trim() !== ''));
  };

  return (
    <div>
      <p className="text-sm text-muted mb-3">
        Set variables immediately before this request runs. Values may reference existing variables or dynamic tokens: <code className="font-mono text-xs">{'{{$timestamp}}'}</code>, <code className="font-mono text-xs">{'{{$guid}}'}</code>, <code className="font-mono text-xs">{'{{$randomInt}}'}</code>, <code className="font-mono text-xs">{'{{$isoTimestamp}}'}</code>.
      </p>
      <div className="flex flex-col gap-2">
        {rows.map((row, i) => (
          <div key={i} className="flex items-center gap-2">
            <input className="input" style={{ flex: '1 1 40%', fontFamily: 'var(--font-mono)' }} placeholder="variableName" value={row.name} onChange={e => updateRow(i, { name: e.target.value })} />
            <input className="input" style={{ flex: '1 1 50%', fontFamily: 'var(--font-mono)' }} placeholder="{{$guid}}" value={row.value} onChange={e => updateRow(i, { value: e.target.value })} />
            <button className="btn btn-secondary" style={{ padding: '0.4rem' }} onClick={() => onChange(rows.filter((_, idx) => idx !== i))} disabled={i === rows.length - 1 && row.name === ''}><Trash2 size={14} /></button>
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Assertions ───────────────────────────────────────────────────────────

const QUICK_ASSERTIONS: { label: string; build: () => AssertionDefinition }[] = [
  { label: 'Status code = 200', build: () => ({ type: 'STATUS_CODE_EQUALS', expected: '200' }) },
  { label: 'Response time < 2000ms', build: () => ({ type: 'RESPONSE_TIME_LESS_THAN', expected: '2000' }) },
  { label: 'JSON field exists', build: () => ({ type: 'JSON_PATH_EXISTS', target: '$.data' }) },
  { label: 'JSON field equals value', build: () => ({ type: 'JSON_PATH_EQUALS', target: '$.data', expected: '' }) },
  { label: 'JSON contains value', build: () => ({ type: 'JSON_PATH_CONTAINS', target: '$.data', expected: '' }) },
];

function AssertionsEditor({ assertions, onChange }: { assertions: AssertionDefinition[]; onChange: (a: AssertionDefinition[]) => void }) {
  const addAssertion = () => onChange([...assertions, { type: 'STATUS_CODE_EQUALS', expected: '200' }]);
  const addQuickAssertion = (build: () => AssertionDefinition) => onChange([...assertions, build()]);
  const updateAssertion = (i: number, patch: Partial<AssertionDefinition>) => onChange(assertions.map((a, idx) => (idx === i ? { ...a, ...patch } : a)));
  const removeAssertion = (i: number) => onChange(assertions.filter((_, idx) => idx !== i));

  return (
    <div>
      {assertions.length === 0 && (
        <div className="mb-4">
          <p className="text-sm text-muted mb-2">Add a common check with one click, or build a custom one below:</p>
          <div className="flex gap-2 flex-wrap">
            {QUICK_ASSERTIONS.map(qa => (
              <button key={qa.label} className="btn btn-secondary" style={{ fontSize: '0.8rem', padding: '0.35rem 0.7rem' }} onClick={() => addQuickAssertion(qa.build)}>
                + {qa.label}
              </button>
            ))}
          </div>
        </div>
      )}
      <div className="flex flex-col gap-3">
        {assertions.map((a, i) => {
          const meta = ASSERTION_TYPES.find(t => t.value === a.type) ?? ASSERTION_TYPES[0];
          return (
            <div key={i} className="flex items-center gap-2 flex-wrap" style={{ padding: '0.6rem', background: 'var(--bg-input)', borderRadius: 'var(--radius-md)' }}>
              <select className="select" style={{ width: 'auto', minWidth: 200 }} value={a.type} onChange={e => updateAssertion(i, { type: e.target.value as AssertionType })}>
                {ASSERTION_TYPES.map(t => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
              {meta.needsTarget && (
                <input className="input" style={{ flex: '1 1 160px', fontFamily: 'var(--font-mono)' }} placeholder={a.type.startsWith('JSON_PATH') ? '$.data.id' : 'Header-Name'} value={a.target ?? ''} onChange={e => updateAssertion(i, { target: e.target.value })} />
              )}
              {meta.needsExpected && (
                <input className="input" style={{ flex: '1 1 140px', fontFamily: 'var(--font-mono)' }} placeholder={meta.expectedPlaceholder ?? 'expected value'} value={a.expected ?? ''} onChange={e => updateAssertion(i, { expected: e.target.value })} />
              )}
              <button className="btn btn-secondary" style={{ padding: '0.4rem' }} onClick={() => removeAssertion(i)}><Trash2 size={14} /></button>
            </div>
          );
        })}
      </div>
      <button className="btn btn-secondary mt-3" onClick={addAssertion}>+ Add Custom Assertion</button>
    </div>
  );
}

// ── Extraction (chaining) ────────────────────────────────────────────────

function ExtractionEditor({ rules, onChange }: { rules: ExtractionRule[]; onChange: (r: ExtractionRule[]) => void }) {
  const addRule = () => onChange([...rules, { source: 'JSON_PATH', path: '', variableName: '', saveTo: 'RUNTIME' }]);
  const updateRule = (i: number, patch: Partial<ExtractionRule>) => onChange(rules.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  const removeRule = (i: number) => onChange(rules.filter((_, idx) => idx !== i));

  return (
    <div>
      <p className="text-sm text-muted mb-3">Extract a value from this response and save it as a variable — the mechanism behind request chaining.</p>
      <div className="flex flex-col gap-3">
        {rules.map((r, i) => (
          <div key={i} className="flex items-center gap-2 flex-wrap" style={{ padding: '0.6rem', background: 'var(--bg-input)', borderRadius: 'var(--radius-md)' }}>
            <select className="select" style={{ width: 'auto', minWidth: 120 }} value={r.source} onChange={e => updateRule(i, { source: e.target.value as ExtractionSource })}>
              <option value="JSON_PATH">JSON Path</option>
              <option value="HEADER">Header</option>
              <option value="STATUS_CODE">Status Code</option>
            </select>
            {r.source !== 'STATUS_CODE' && (
              <input className="input" style={{ flex: '1 1 140px', fontFamily: 'var(--font-mono)' }} placeholder={r.source === 'JSON_PATH' ? '$.token' : 'Set-Cookie'} value={r.path} onChange={e => updateRule(i, { path: e.target.value })} />
            )}
            <span className="text-subtle">→</span>
            <input className="input" style={{ flex: '1 1 120px', fontFamily: 'var(--font-mono)' }} placeholder="variableName" value={r.variableName} onChange={e => updateRule(i, { variableName: e.target.value })} />
            <select className="select" style={{ width: 'auto', minWidth: 130 }} value={r.saveTo} onChange={e => updateRule(i, { saveTo: e.target.value as ExtractionSaveTo })}>
              <option value="RUNTIME">This run only</option>
              <option value="ENVIRONMENT">Save to environment</option>
            </select>
            <button className="btn btn-secondary" style={{ padding: '0.4rem' }} onClick={() => removeRule(i)}><Trash2 size={14} /></button>
          </div>
        ))}
      </div>
      <button className="btn btn-secondary mt-3" onClick={addRule}>+ Add Extraction</button>
    </div>
  );
}
