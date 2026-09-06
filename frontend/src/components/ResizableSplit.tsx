import { useCallback, useRef, useState, type ReactNode } from 'react';
import { GripHorizontal } from 'lucide-react';

interface ResizableSplitProps {
  top: ReactNode;
  bottom: ReactNode;
  initialTopPercent?: number;
  minPercent?: number;
}

/** A vertically-resizable two-pane split (request editor above, response viewer below) — hand-built drag handle rather than a new dependency, matching this app's minimal-dependency frontend. */
export default function ResizableSplit({ top, bottom, initialTopPercent = 55, minPercent = 20 }: ResizableSplitProps) {
  const [topPercent, setTopPercent] = useState(initialTopPercent);
  const containerRef = useRef<HTMLDivElement>(null);
  const dragging = useRef(false);

  const onPointerMove = useCallback((e: PointerEvent) => {
    if (!dragging.current || !containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const percent = ((e.clientY - rect.top) / rect.height) * 100;
    setTopPercent(Math.min(100 - minPercent, Math.max(minPercent, percent)));
  }, [minPercent]);

  const stopDragging = useCallback(() => {
    dragging.current = false;
    document.removeEventListener('pointermove', onPointerMove);
    document.removeEventListener('pointerup', stopDragging);
  }, [onPointerMove]);

  const startDragging = () => {
    dragging.current = true;
    document.addEventListener('pointermove', onPointerMove);
    document.addEventListener('pointerup', stopDragging);
  };

  return (
    <div ref={containerRef} style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={{ height: `${topPercent}%`, minHeight: 0, overflow: 'auto' }}>{top}</div>
      <div className="api-resize-handle" onPointerDown={startDragging} title="Drag to resize">
        <GripHorizontal size={14} />
      </div>
      <div style={{ height: `${100 - topPercent}%`, minHeight: 0, overflow: 'auto' }}>{bottom}</div>
    </div>
  );
}
