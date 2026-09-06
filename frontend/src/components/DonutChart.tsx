interface DonutSegment {
  label: string;
  value: number;
  color: string;
}

interface DonutChartProps {
  segments: DonutSegment[];
  size?: number;
  strokeWidth?: number;
  centerLabel: string | number;
  centerSubLabel?: string;
}

/**
 * A minimal multi-segment SVG ring — the donut-chart equivalent of
 * CircularProgress (a single-value ring). No charting dependency: each
 * segment is one <circle> whose dash pattern is sized to its share of the
 * total and rotated into position via strokeDashoffset, a standard
 * from-scratch SVG donut technique. Adjacent segments get a small rounded-cap
 * gap (rather than abutting flush) — the detail that reads as "designed"
 * instead of "a plain pie chart". A native <title> per segment gives a free
 * browser tooltip on hover without extra JS state.
 */
export default function DonutChart({ segments, size = 120, strokeWidth = 14, centerLabel, centerSubLabel }: DonutChartProps) {
  const total = segments.reduce((sum, s) => sum + s.value, 0);
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const center = size / 2;
  const visibleCount = segments.filter(s => s.value > 0).length;
  // No visual gap when there's only one segment — a single slice should read
  // as a full, unbroken ring, not a ring with a stray notch.
  const gapPx = visibleCount > 1 ? Math.min(6, circumference * 0.02) : 0;

  let cumulativeFraction = 0;
  const arcs = segments
    .filter(s => s.value > 0)
    .map(s => {
      const fraction = total > 0 ? s.value / total : 0;
      const fullDash = fraction * circumference;
      const dash = Math.max(0, fullDash - gapPx);
      const arc = (
        <circle
          key={s.label}
          cx={center} cy={center} r={radius} fill="none"
          stroke={s.color} strokeWidth={strokeWidth} strokeLinecap="round"
          strokeDasharray={`${dash} ${circumference - dash}`}
          strokeDashoffset={-(cumulativeFraction * circumference + gapPx / 2)}
          transform={`rotate(-90 ${center} ${center})`}
          style={{ transition: 'stroke-dasharray var(--transition-base)' }}
        >
          <title>{`${s.label}: ${s.value} (${Math.round(fraction * 100)}%)`}</title>
        </circle>
      );
      cumulativeFraction += fraction;
      return arc;
    });

  return (
    <div className="donut-chart" style={{ position: 'relative', width: size, height: size, flexShrink: 0 }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle cx={center} cy={center} r={radius} fill="none" stroke="var(--bg-elevated)" strokeWidth={strokeWidth} />
        {arcs}
      </svg>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ fontWeight: 700, fontSize: size * 0.24, color: 'var(--text-main)', lineHeight: 1 }}>{centerLabel}</div>
        {centerSubLabel && <div className="text-xs text-subtle" style={{ marginTop: 4, letterSpacing: '0.02em' }}>{centerSubLabel}</div>}
      </div>
    </div>
  );
}
