import { useMemo, useState } from 'react';
import { ChevronRight, ChevronDown, Copy, Search } from 'lucide-react';
import { useToast } from './Toast';

interface JsonTreeViewProps {
  value: unknown;
  /** Above this many top-level keys/items, nodes past depth 1 start collapsed by default — keeps a huge response from rendering thousands of expanded DOM nodes at once. */
  autoCollapseAfter?: number;
}

type JsonValue = null | boolean | number | string | JsonValue[] | { [key: string]: JsonValue };

function valueColorClass(value: JsonValue): string {
  if (value === null) return 'json-null';
  switch (typeof value) {
    case 'string': return 'json-string';
    case 'number': return 'json-number';
    case 'boolean': return 'json-boolean';
    default: return '';
  }
}

function matchesSearch(value: JsonValue, key: string | null, query: string): boolean {
  if (!query) return true;
  const q = query.toLowerCase();
  if (key && key.toLowerCase().includes(q)) return true;
  if (value !== null && typeof value === 'object') {
    if (Array.isArray(value)) return value.some((v, i) => matchesSearch(v, String(i), query));
    return Object.entries(value).some(([k, v]) => matchesSearch(v, k, query));
  }
  return String(value).toLowerCase().includes(q);
}

function JsonNode({ nodeKey, value, depth, defaultCollapsed, query }: {
  nodeKey: string | null; value: JsonValue; depth: number; defaultCollapsed: boolean; query: string;
}) {
  const [collapsed, setCollapsed] = useState(defaultCollapsed);
  const isObject = value !== null && typeof value === 'object';
  const isArray = Array.isArray(value);

  if (query && !matchesSearch(value, nodeKey, query)) return null;

  if (!isObject) {
    return (
      <div className="json-line" style={{ paddingLeft: depth * 16 }}>
        {nodeKey !== null && <span className="json-key">"{nodeKey}"</span>}
        {nodeKey !== null && <span className="json-punct">: </span>}
        <span className={valueColorClass(value)}>{value === null ? 'null' : typeof value === 'string' ? `"${value}"` : String(value)}</span>
      </div>
    );
  }

  const entries = isArray ? value.map((v, i) => [String(i), v] as const) : Object.entries(value);
  const bracket = isArray ? ['[', ']'] : ['{', '}'];

  if (entries.length === 0) {
    return (
      <div className="json-line" style={{ paddingLeft: depth * 16 }}>
        {nodeKey !== null && <span className="json-key">"{nodeKey}"</span>}
        {nodeKey !== null && <span className="json-punct">: </span>}
        <span className="json-punct">{bracket[0]}{bracket[1]}</span>
      </div>
    );
  }

  return (
    <div>
      <div
        className="json-line json-line-toggle"
        style={{ paddingLeft: depth * 16, cursor: 'pointer' }}
        onClick={() => setCollapsed(c => !c)}
      >
        {collapsed ? <ChevronRight size={12} className="json-chevron" /> : <ChevronDown size={12} className="json-chevron" />}
        {nodeKey !== null && <span className="json-key">"{nodeKey}"</span>}
        {nodeKey !== null && <span className="json-punct">: </span>}
        <span className="json-punct">{bracket[0]}</span>
        {collapsed && <span className="json-punct json-collapsed-hint"> … {entries.length} {isArray ? 'items' : 'keys'} {bracket[1]}</span>}
      </div>
      {!collapsed && (
        <>
          {entries.map(([k, v]) => (
            <JsonNode key={k} nodeKey={k} value={v} depth={depth + 1} defaultCollapsed={defaultCollapsed} query={query} />
          ))}
          <div className="json-line json-punct" style={{ paddingLeft: depth * 16 }}>{bracket[1]}</div>
        </>
      )}
    </div>
  );
}

/** A collapsible, searchable, syntax-highlighted JSON viewer — built by hand (no new dependency) rather than reaching for a heavy code-editor library for a read-only response viewer. */
export default function JsonTreeView({ value, autoCollapseAfter = 30 }: JsonTreeViewProps) {
  const { showToast } = useToast();
  const [query, setQuery] = useState('');

  const entryCount = useMemo(() => {
    if (value !== null && typeof value === 'object') return Array.isArray(value) ? value.length : Object.keys(value).length;
    return 0;
  }, [value]);

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(JSON.stringify(value, null, 2));
      showToast('Copied to clipboard.', 'success');
    } catch {
      showToast('Could not copy to clipboard.', 'error');
    }
  };

  return (
    <div>
      <div className="flex items-center gap-2 mb-2">
        <div className="search-box" style={{ flex: '1 1 200px' }}>
          <Search size={13} />
          <input className="input" placeholder="Search keys or values…" value={query} onChange={e => setQuery(e.target.value)} style={{ fontSize: '0.8rem', padding: '0.35rem 0.5rem' }} />
        </div>
        <button className="btn btn-secondary" style={{ padding: '0.35rem 0.6rem' }} onClick={handleCopy} title="Copy JSON">
          <Copy size={13} />
        </button>
      </div>
      <div className="json-tree">
        <JsonNode nodeKey={null} value={value as JsonValue} depth={0} defaultCollapsed={entryCount > autoCollapseAfter} query={query} />
      </div>
    </div>
  );
}
