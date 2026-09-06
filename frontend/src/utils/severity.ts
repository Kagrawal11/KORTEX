import { AlertOctagon, AlertTriangle, AlertCircle, Info, HelpCircle, CheckCircle2 } from 'lucide-react';
import type { ComponentType } from 'react';
import type { AccessibilityImpact } from '../types';

export type SeverityKey = 'critical' | 'serious' | 'moderate' | 'minor' | 'review' | 'passed';

interface SeverityMeta {
  key: SeverityKey;
  label: string;
  badgeClass: string;
  icon: ComponentType<{ size?: number; className?: string }>;
}

const SEVERITY_META: Record<SeverityKey, SeverityMeta> = {
  critical: { key: 'critical', label: 'Critical', badgeClass: 'badge-severity-critical', icon: AlertOctagon },
  serious: { key: 'serious', label: 'Serious', badgeClass: 'badge-severity-serious', icon: AlertTriangle },
  moderate: { key: 'moderate', label: 'Moderate', badgeClass: 'badge-severity-moderate', icon: AlertCircle },
  minor: { key: 'minor', label: 'Minor', badgeClass: 'badge-severity-minor', icon: Info },
  review: { key: 'review', label: 'Needs Review', badgeClass: 'badge-severity-review', icon: HelpCircle },
  passed: { key: 'passed', label: 'Passed', badgeClass: 'badge-severity-passed', icon: CheckCircle2 },
};

/** Maps an axe-core impact string (critical/serious/moderate/minor, possibly null) to our severity key — mirrors the backend's own fallback-to-moderate rule for a missing/unrecognised impact. */
export function impactToSeverityKey(impact: AccessibilityImpact | string | null | undefined): SeverityKey {
  const normalized = (impact ?? '').toLowerCase();
  if (normalized === 'critical' || normalized === 'serious' || normalized === 'minor') {
    return normalized;
  }
  return 'moderate';
}

export function getSeverityMeta(key: SeverityKey): SeverityMeta {
  return SEVERITY_META[key];
}

export const SEVERITY_ORDER: SeverityKey[] = ['critical', 'serious', 'moderate', 'minor', 'review', 'passed'];
