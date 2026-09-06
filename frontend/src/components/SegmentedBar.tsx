interface Segment {
  label: string;
  value: number;
  color: string;
}

interface SegmentedBarProps {
  segments: Segment[];
}

/**
 * A minimal horizontal proportional bar + legend — used for real count
 * distributions (e.g. runs by type, runs by status). Deliberately not a
 * charting library: this is the only shape the Dashboard needs, and a couple
 * of divs render it without adding a dependency for two bars.
 */
export default function SegmentedBar({ segments }: SegmentedBarProps) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  if (total === 0) return null;

  return (
    <div>
      <div style={{ display: 'flex', height: 10, borderRadius: 'var(--radius-full)', overflow: 'hidden', background: 'var(--bg-input)' }}>
        {segments.filter(s => s.value > 0).map(s => (
          <div
            key={s.label}
            title={`${s.label}: ${s.value}`}
            style={{ width: `${(s.value / total) * 100}%`, background: s.color, transition: 'width var(--transition-base)' }}
          />
        ))}
      </div>
      <div className="flex items-center gap-4 flex-wrap mt-3">
        {segments.map(s => (
          <div key={s.label} className="flex items-center gap-2 text-sm">
            <span style={{ width: 8, height: 8, borderRadius: '50%', background: s.color, flexShrink: 0 }} />
            <span className="text-muted">{s.label}</span>
            <span className="font-medium" style={{ color: 'var(--text-main)' }}>{s.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
