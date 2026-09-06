import type { ReactNode } from 'react';

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}

export default function EmptyState({ icon, title, description, action }: EmptyStateProps) {
  return (
    <div className="empty-state">
      {icon && <div className="empty-state-icon">{icon}</div>}
      <h3 style={{ color: 'var(--text-main)', margin: 0 }}>{title}</h3>
      {description && <p style={{ maxWidth: 440, margin: 0 }}>{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
