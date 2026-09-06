import { Info, Server, User, Monitor } from 'lucide-react';
import PageHeader from '../components/PageHeader';
import { API_BASE_URL } from '../services/uiAutomationApi';

// Deliberately static/informational only — this app has no user accounts,
// preferences, or configurable settings today, so this page states real
// facts about the running instance rather than offering controls that
// wouldn't actually do anything.
export default function Settings() {
  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Settings' }]}
        title="Settings"
        subtitle={<p style={{ margin: 0 }}>About this Kortex instance.</p>}
      />

      <div className="card mb-6" style={{ display: 'flex', alignItems: 'flex-start', gap: '12px', background: 'var(--info-bg)', borderColor: 'var(--primary)', color: 'var(--primary)' }}>
        <Info size={18} style={{ flexShrink: 0, marginTop: '2px' }} />
        <span className="text-sm">This is a single-user local tool — there are no accounts, roles, or configurable preferences yet, so this page is informational rather than editable.</span>
      </div>

      <div className="flex gap-4 flex-wrap">
        <div className="card" style={{ flex: '1 1 260px' }}>
          <div className="flex items-center gap-2 mb-2 text-muted text-sm"><User size={15} /> Workspace</div>
          <div className="font-medium">Kartik Agrawal</div>
          <div className="text-xs text-subtle mt-1">Local workspace</div>
        </div>
        <div className="card" style={{ flex: '1 1 260px' }}>
          <div className="flex items-center gap-2 mb-2 text-muted text-sm"><Server size={15} /> Backend API</div>
          <div className="font-medium font-mono text-sm">{API_BASE_URL}</div>
          <div className="text-xs text-subtle mt-1">Spring Boot, running locally</div>
        </div>
        <div className="card" style={{ flex: '1 1 260px' }}>
          <div className="flex items-center gap-2 mb-2 text-muted text-sm"><Monitor size={15} /> Automation Engine</div>
          <div className="font-medium">Chromium (Playwright)</div>
          <div className="text-xs text-subtle mt-1">One shared session across recording and playback</div>
        </div>
      </div>
    </div>
  );
}
