import { CheckCircle2, XCircle, RefreshCw, AlertTriangle, MinusCircle } from 'lucide-react';
import type { ComponentType } from 'react';

type Tone = 'success' | 'error' | 'warning' | 'info' | 'neutral';

const STATUS_MAP: Record<string, { label?: string; tone: Tone; icon?: ComponentType<{ size?: number; className?: string }> }> = {
  PASSED: { tone: 'success', icon: CheckCircle2 },
  SUCCESS: { tone: 'success', icon: CheckCircle2 },
  // Accessibility scans finish as COMPLETED rather than PASSED/FAILED — a
  // scan can complete and still report violations, so "completed" only means
  // a real report is available, not a pass/fail verdict.
  COMPLETED: { tone: 'success', icon: CheckCircle2 },
  FAILED: { tone: 'error', icon: XCircle },
  CRITICAL_FAILURE: { tone: 'error', label: 'Critical', icon: AlertTriangle },
  RUNNING: { tone: 'warning', icon: RefreshCw },
  HEALED_BY_AI: { tone: 'warning', label: 'AI Healed', icon: AlertTriangle },
  SKIPPED: { tone: 'neutral', icon: MinusCircle },
  // API Testing request-result statuses — a network failure/timeout never got
  // a real HTTP response at all, distinct from an ordinary assertion FAILED.
  NETWORK_ERROR: { tone: 'error', label: 'Network Error', icon: XCircle },
  TIMEOUT: { tone: 'error', label: 'Timeout', icon: AlertTriangle },
};

interface StatusBadgeProps {
  status: string;
  size?: 'sm' | 'md' | 'lg';
}

/**
 * Single source of truth for status → color/icon across the app. Unifies
 * PASSED/FAILED/RUNNING (runs), SUCCESS/CRITICAL_FAILURE (data-driven rows),
 * and HEALED_BY_AI/SKIPPED (steps) — previously each page re-implemented this
 * mapping with its own inline ternary chain. An unrecognised status still
 * renders (neutral tone, raw label) instead of disappearing.
 */
export default function StatusBadge({ status, size = 'md' }: StatusBadgeProps) {
  const entry = STATUS_MAP[status] ?? { tone: 'neutral' as Tone };
  const Icon = entry.icon;
  const label = entry.label ?? status;
  const sizeStyle: React.CSSProperties =
    size === 'lg' ? { fontSize: '1rem', padding: '0.45rem 1.1rem' }
    : size === 'sm' ? { fontSize: '0.65rem', padding: '0.15rem 0.55rem' }
    : {};

  return (
    <span className={`badge badge-${entry.tone}`} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, ...sizeStyle }}>
      {Icon && <Icon size={size === 'lg' ? 16 : 12} className={status === 'RUNNING' ? 'animate-spin' : undefined} />}
      {label}
    </span>
  );
}
