import { Trash2 } from 'lucide-react';
import type { KeyValueItem } from '../types';

interface KeyValueEditorProps {
  items: KeyValueItem[];
  onChange: (items: KeyValueItem[]) => void;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
}

/** Reusable enable/disable-able key-value list editor — backs Params, Headers, and Form fields, all of which share this exact shape. Always keeps one trailing blank row so adding a new entry never needs its own button. */
export default function KeyValueEditor({ items, onChange, keyPlaceholder = 'Key', valuePlaceholder = 'Value' }: KeyValueEditorProps) {
  const rows = items.length === 0 || items[items.length - 1].key !== '' ? [...items, { key: '', value: '', enabled: true }] : items;

  const updateRow = (index: number, patch: Partial<KeyValueItem>) => {
    const next = rows.map((row, i) => (i === index ? { ...row, ...patch } : row));
    onChange(next.filter((row, i) => i === next.length - 1 || row.key.trim() !== '' || row.value.trim() !== ''));
  };

  const removeRow = (index: number) => {
    onChange(rows.filter((_, i) => i !== index));
  };

  return (
    <div className="flex flex-col gap-2">
      {rows.map((row, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={row.enabled}
            onChange={e => updateRow(i, { enabled: e.target.checked })}
            style={{ flexShrink: 0 }}
            aria-label={row.key ? `Enable ${row.key}` : 'Enable row'}
          />
          <input
            className="input"
            placeholder={keyPlaceholder}
            value={row.key}
            onChange={e => updateRow(i, { key: e.target.value })}
            style={{ flex: '1 1 40%', opacity: row.enabled ? 1 : 0.5 }}
          />
          <input
            className="input"
            placeholder={valuePlaceholder}
            value={row.value}
            onChange={e => updateRow(i, { value: e.target.value })}
            style={{ flex: '1 1 50%', opacity: row.enabled ? 1 : 0.5 }}
          />
          <button
            className="btn btn-secondary"
            style={{ padding: '0.4rem', flexShrink: 0 }}
            onClick={() => removeRow(i)}
            disabled={i === rows.length - 1 && row.key === '' && row.value === ''}
            title="Remove"
          >
            <Trash2 size={14} />
          </button>
        </div>
      ))}
    </div>
  );
}
