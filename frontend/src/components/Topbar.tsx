import { useEffect, useRef, useState } from 'react';
import { Bell } from 'lucide-react';

/**
 * Deliberately minimal — this app's real search/filter needs live inside
 * each page that actually has something to search (Data Driven tests,
 * Execution History, Accessibility scans), where the results are scoped and
 * meaningful. A global "search everything" bar here had nowhere useful to
 * send most queries, so it was removed rather than kept as decoration.
 */
export default function Topbar() {
  const [notifOpen, setNotifOpen] = useState(false);
  const notifRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (notifRef.current && !notifRef.current.contains(e.target as Node)) setNotifOpen(false);
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  return (
    <div className="topbar topbar-minimal">
      <div className="topbar-actions" ref={notifRef}>
        <button className="topbar-icon-btn" onClick={() => setNotifOpen(o => !o)} aria-label="Notifications">
          <Bell size={18} />
        </button>
        {notifOpen && (
          <div className="topbar-popover">
            <div className="text-sm text-muted">No notifications yet.</div>
          </div>
        )}
      </div>
    </div>
  );
}
