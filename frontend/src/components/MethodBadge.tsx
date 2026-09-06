export const METHOD_COLOR: Record<string, string> = {
  GET: 'var(--success)',
  POST: 'var(--warning)',
  PUT: '#3b82f6',
  PATCH: 'var(--accent-purple)',
  DELETE: 'var(--error)',
  HEAD: 'var(--text-subtle)',
  OPTIONS: 'var(--text-subtle)',
};

interface MethodBadgeProps {
  method: string;
  size?: 'sm' | 'md';
}

/** A compact, colored HTTP-method tag — the same method always renders the same color everywhere in the workspace (tree, editor, response history, reports). */
export default function MethodBadge({ method, size = 'md' }: MethodBadgeProps) {
  const color = METHOD_COLOR[method?.toUpperCase()] ?? 'var(--text-subtle)';
  return (
    <span
      style={{
        display: 'inline-block',
        fontFamily: 'var(--font-mono)',
        fontWeight: 700,
        color,
        fontSize: size === 'sm' ? '0.65rem' : '0.75rem',
        minWidth: size === 'sm' ? 38 : 46,
        textAlign: 'left',
      }}
    >
      {method?.toUpperCase()}
    </span>
  );
}
