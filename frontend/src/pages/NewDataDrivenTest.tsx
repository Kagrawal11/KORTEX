import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Database, Video, Search, ArrowRight, ListChecks, Info } from 'lucide-react';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestScenario } from '../types';
import PageHeader from '../components/PageHeader';
import EmptyState from '../components/EmptyState';
import Skeleton from '../components/Skeleton';
import { useToast } from '../components/Toast';

/**
 * Entry point for "Create Data Driven Test". This app has no independent
 * "Data Driven Test" entity — a data-driven run is always executed against
 * a recorded UI Automation test's steps (see DataDrivenRunEntity.scenario).
 * So "creating" one really means one of two things:
 *   (a) an existing recorded test already has the steps you need — jump
 *       straight to its Data-Driven tab (upload/map/execute), or
 *   (b) nothing suitable exists yet — record a new test first, using the
 *       exact same recording flow/browser as UI Automation, then come back
 *       to its Data-Driven tab once steps exist.
 * Both paths land in the SAME real upload → configure loop → map fields →
 * execute flow (DataDrivenPanel) — this page never duplicates that logic.
 */
export default function NewDataDrivenTest() {
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [tests, setTests] = useState<TestScenario[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [search, setSearch] = useState('');

  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [error, setError] = useState('');
  const [isCreating, setIsCreating] = useState(false);

  useEffect(() => {
    uiAutomationApi.getTests()
      .then(setTests)
      .catch(() => setTests([]))
      .finally(() => setIsLoading(false));
  }, []);

  const recordedTests = useMemo(() => tests.filter(t => t.steps && t.steps.length > 0), [tests]);
  const visibleTests = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return recordedTests;
    return recordedTests.filter(t => t.name.toLowerCase().includes(query) || t.targetUrl.toLowerCase().includes(query));
  }, [recordedTests, search]);

  const openExistingTest = (test: TestScenario) => {
    navigate(`/ui-automation/tests/${test.id}?tab=data-driven`);
  };

  const handleRecordNew = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !url.trim()) {
      setError('Name and URL are required.');
      return;
    }
    let targetUrl = url.trim();
    if (!targetUrl.startsWith('http://') && !targetUrl.startsWith('https://')) {
      targetUrl = 'https://' + targetUrl;
    }
    try {
      setIsCreating(true);
      setError('');
      const test = await uiAutomationApi.createTest({ name: name.trim(), targetUrl });
      showToast(`"${test.name}" created — record its steps, then open the Data-Driven tab.`, 'success');
      navigate(`/ui-automation/recording/${test.id}`);
    } catch (err: any) {
      setError(err?.response?.data?.error || 'Failed to create the test. Please try again.');
      setIsCreating(false);
    }
  };

  return (
    <div className="container">
      <PageHeader
        breadcrumbs={[{ label: 'Dashboard', to: '/' }, { label: 'Data Driven', to: '/data-driven' }, { label: 'Create Test' }]}
        title="Create Data Driven Test"
        subtitle={<p style={{ margin: 0 }}>A data-driven run replays a recorded test's steps once per data row. Start from a test that already has recorded steps, or record a new one first.</p>}
      />

      <div className="flex gap-6 flex-wrap" style={{ alignItems: 'flex-start' }}>
        <div className="card card-accent-top accent-purple" style={{ flex: '1 1 380px' }}>
          <div className="flex items-center gap-3 mb-4">
            <span className="icon-badge accent-purple"><Database size={18} /></span>
            <div>
              <h3 style={{ margin: 0, fontSize: '1rem' }}>Use an Existing Recorded Test</h3>
              <p className="text-xs text-muted" style={{ margin: 0 }}>Jump straight to data upload and field mapping.</p>
            </div>
          </div>

          {isLoading ? (
            <Skeleton variant="row" count={3} />
          ) : recordedTests.length === 0 ? (
            <EmptyState
              icon={<ListChecks size={30} />}
              title="No recorded tests yet"
              description="Record a test first using the option on the right, then come back here."
            />
          ) : (
            <>
              <div className="search-box mb-3">
                <Search size={15} />
                <input className="input" placeholder="Search recorded tests…" value={search} onChange={e => setSearch(e.target.value)} />
              </div>
              <div className="flex flex-col gap-2" style={{ maxHeight: 360, overflowY: 'auto' }}>
                {visibleTests.length === 0 ? (
                  <EmptyState title="No tests match your search" />
                ) : (
                  visibleTests.map(test => (
                    <button
                      key={test.id}
                      onClick={() => openExistingTest(test)}
                      className="flex items-center justify-between gap-3"
                      style={{
                        width: '100%', textAlign: 'left', padding: '0.7rem 0.9rem',
                        background: 'var(--bg-input)', border: '1px solid var(--border-color)',
                        borderRadius: 'var(--radius-md)', cursor: 'pointer', color: 'inherit', font: 'inherit',
                      }}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div className="font-medium truncate">{test.name}</div>
                        <div className="text-xs text-muted truncate">{test.targetUrl} &bull; {test.steps.length} steps</div>
                      </div>
                      <ArrowRight size={16} className="text-muted" style={{ flexShrink: 0 }} />
                    </button>
                  ))
                )}
              </div>
            </>
          )}
        </div>

        <div className="card card-accent-top accent-blue" style={{ flex: '1 1 380px' }}>
          <div className="flex items-center gap-3 mb-4">
            <span className="icon-badge accent-blue"><Video size={18} /></span>
            <div>
              <h3 style={{ margin: 0, fontSize: '1rem' }}>Record a New Test First</h3>
              <p className="text-xs text-muted" style={{ margin: 0 }}>Opens the real browser recorder — same as UI Automation.</p>
            </div>
          </div>

          {error && (
            <div className="mb-4" style={{ padding: '0.65rem 0.85rem', background: 'var(--error-bg)', color: 'var(--error)', borderRadius: 'var(--radius-md)', fontSize: '0.85rem' }}>
              {error}
            </div>
          )}

          <form onSubmit={handleRecordNew}>
            <div className="mb-3">
              <label className="label">Test Name</label>
              <input className="input" placeholder="e.g. Signup Form" value={name} onChange={e => setName(e.target.value)} disabled={isCreating} />
            </div>
            <div className="mb-4">
              <label className="label">Website URL</label>
              <input className="input" placeholder="e.g. https://example.com" value={url} onChange={e => setUrl(e.target.value)} disabled={isCreating} />
            </div>
            <button type="submit" className="btn btn-primary" disabled={isCreating} style={{ width: '100%' }}>
              {isCreating ? 'Creating…' : <>Create &amp; Start Recording</>}
            </button>
          </form>

          <div className="flex items-start gap-2 mt-4 text-xs text-muted">
            <Info size={13} style={{ marginTop: 2, flexShrink: 0 }} />
            <span>After you stop recording, open this test and switch to its Data-Driven tab to upload your dataset and map fields.</span>
          </div>
        </div>
      </div>
    </div>
  );
}
