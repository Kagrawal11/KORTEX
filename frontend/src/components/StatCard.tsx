import type { ReactNode } from 'react';

interface StatCardProps {
  icon?: ReactNode;
  label: string;
  value: ReactNode;
  tone?: 'default' | 'success' | 'error' | 'primary' | 'warning';
  flex?: string;
}

const TONE_COLOR: Record<NonNullable<StatCardProps['tone']>, string> = {
  default: 'var(--text-main)',
  success: 'var(--success)',
  error: 'var(--error)',
  primary: 'var(--primary)',
  warning: 'var(--warning)',
};

export default function StatCard({ icon, label, value, tone = 'default', flex = '1 1 150px' }: StatCardProps) {
  return (
    <div className="card" style={{ flex }}>
      <div className="flex items-center gap-2 mb-2 text-muted" style={{ fontSize: '0.85rem' }}>
        {icon} <span>{label}</span>
      </div>
      <h3 style={{ color: TONE_COLOR[tone], margin: 0 }}>{value}</h3>
    </div>
  );
}
