interface SkeletonProps {
  variant?: 'text' | 'card' | 'row';
  count?: number;
  width?: string | number;
  height?: string | number;
}

/** Shimmering placeholder blocks for loading states — replaces plain "Loading…" text. */
export default function Skeleton({ variant = 'text', count = 1, width, height }: SkeletonProps) {
  const items = Array.from({ length: count });

  if (variant === 'card') {
    return (
      <div className="flex gap-4 flex-wrap">
        {items.map((_, i) => (
          <div key={i} className="skeleton" style={{ flex: '1 1 150px', height: height ?? 90, borderRadius: 'var(--radius-lg)' }} />
        ))}
      </div>
    );
  }

  if (variant === 'row') {
    return (
      <div className="flex flex-col gap-2">
        {items.map((_, i) => (
          <div key={i} className="skeleton" style={{ height: height ?? 48, width: '100%' }} />
        ))}
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {items.map((_, i) => (
        <div key={i} className="skeleton" style={{ height: height ?? 14, width: width ?? `${92 - i * 9}%` }} />
      ))}
    </div>
  );
}
