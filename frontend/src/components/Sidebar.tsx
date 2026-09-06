import { Link, useLocation } from 'react-router-dom';
import { LayoutDashboard, MousePointerClick, Accessibility, History, Database, Send, Settings as SettingsIcon } from 'lucide-react';

interface NavItem {
  to: string;
  label: string;
  icon: typeof LayoutDashboard;
  /** Rendered as a small uppercase label above this item — marks the start of a new group. */
  sectionLabel?: string;
}

// Grouping communicates the product's real information architecture:
// the three testing capabilities live together under "Testing", while
// Execution History is deliberately its own group — it is a GLOBAL,
// cross-capability feature and must not read as belonging to any one of them.
const NAV_ITEMS: NavItem[] = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard },
  { to: '/ui-automation', label: 'UI Automation', icon: MousePointerClick, sectionLabel: 'Testing' },
  { to: '/data-driven', label: 'Data Driven', icon: Database },
  { to: '/accessibility', label: 'Accessibility', icon: Accessibility },
  { to: '/api-testing', label: 'API Testing', icon: Send },
  { to: '/execution-history', label: 'Execution History', icon: History, sectionLabel: 'Reporting' },
  { to: '/settings', label: 'Settings', icon: SettingsIcon, sectionLabel: 'Workspace' },
];

// NavLink's own active-matching is pathname-only, which can't tell apart two
// items that share a path but differ by query string. Matching the full href
// (path + query, when present) against the current location instead makes
// exactly one item active at a time. No current item uses a query string,
// but this stays generic in case a future one (e.g. a preset filter link)
// needs to.
function isItemActive(href: string, pathname: string, search: string): boolean {
  const [path, query] = href.split('?');
  if (path === '/') return pathname === '/';
  if (pathname !== path) return false;
  if (!query) return !search;
  return search === `?${query}`;
}

export default function Sidebar() {
  const location = useLocation();

  return (
    <aside className="sidebar">
      <Link to="/" className="sidebar-logo">
        <span className="sidebar-logo-mark">
          <img src="/kortex_logo.png" alt="Kortex" />
        </span>
        Kortex
      </Link>

      <nav className="sidebar-nav">
        {NAV_ITEMS.map(item => (
          <div key={item.to}>
            {item.sectionLabel && <div className="sidebar-section-label">{item.sectionLabel}</div>}
            <Link
              to={item.to}
              className={`sidebar-nav-item${isItemActive(item.to, location.pathname, location.search) ? ' active' : ''}`}
            >
              <item.icon size={18} />
              {item.label}
            </Link>
          </div>
        ))}
      </nav>

      <div className="sidebar-footer">
        <div className="sidebar-user">
          <span className="sidebar-user-avatar">KA</span>
          <div style={{ minWidth: 0 }}>
            <div className="text-sm font-medium truncate" style={{ color: 'var(--text-main)' }}>Kartik Agrawal</div>
            <div className="text-xs text-subtle">Local workspace</div>
          </div>
        </div>
      </div>
    </aside>
  );
}
