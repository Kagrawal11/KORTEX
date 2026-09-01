import { useEffect, useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestScenario, TestRun } from '../types';
import { Play, ArrowLeft, Clock, MonitorPlay, Activity, Download, Copy, Check, Code } from 'lucide-react';

export default function TestDetails() {
  const { testId } = useParams();
  const navigate = useNavigate();
  
  const [test, setTest] = useState<TestScenario | null>(null);
  const [runs, setRuns] = useState<TestRun[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isStartingRun, setIsStartingRun] = useState(false);
  const [error, setError] = useState('');
  const [activeTab, setActiveTab] = useState<'steps' | 'runs' | 'script'>('steps');
  const [scriptContent, setScriptContent] = useState<string | null>(null);
  const [isLoadingScript, setIsLoadingScript] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (testId) {
      loadData();
    }
  }, [testId]);

  const loadData = async () => {
    try {
      setIsLoading(true);
      setError('');

      // Load test details (required)
      const testData = await uiAutomationApi.getTest(Number(testId));
      setTest(testData);

      // Load runs separately — non-fatal if it fails
      try {
        const runsData = await uiAutomationApi.getTestRuns(Number(testId));
        setRuns(runsData);
      } catch {
        setRuns([]); // runs failed, but still show the test details
      }
    } catch (err: any) {
      const msg = err?.response?.data?.message
        || err?.response?.statusText
        || err?.message
        || 'Failed to load test details.';
      setError(`Error: ${msg}`);
    } finally {
      setIsLoading(false);
    }
  };

  const handleRunTest = async () => {
    try {
      setIsStartingRun(true);
      setError('');
      // Backend returns immediately with a RUNNING run ID
      // Playwright executes in background on the server
      const run = await uiAutomationApi.runTest(Number(testId));
      // Navigate to the report page immediately — it will show RUNNING → PASSED/FAILED
      navigate(`/ui-automation/runs/${run.id}`);
    } catch (err: any) {
      const msg = err?.response?.data?.message || err?.message || 'Failed to start test run.';
      setError(`Error: ${msg}`);
      setIsStartingRun(false);
    }
  };

  const handleExportScript = async () => {
    if (scriptContent) {
      setActiveTab('script');
      return;
    }
    try {
      setIsLoadingScript(true);
      setActiveTab('script');
      const resp = await fetch(`http://localhost:8080/api/ui-automation/tests/${testId}/export`);
      const text = await resp.text();
      setScriptContent(text);
    } catch {
      setScriptContent('// Error: Could not load script from backend.');
    } finally {
      setIsLoadingScript(false);
    }
  };

  const handleDownloadScript = () => {
    if (!scriptContent || !test) return;
    const blob = new Blob([scriptContent], { type: 'text/plain' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href     = url;
    a.download = `${test.name.replace(/[^A-Za-z0-9_-]/g, '_')}Test.java`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleCopy = () => {
    if (!scriptContent) return;
    navigator.clipboard.writeText(scriptContent).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  if (isLoading) {
    return <div className="container" style={{ textAlign: 'center', paddingTop: '4rem' }}>Loading test details...</div>;
  }

  if (error || !test) {
    return (
      <div className="container" style={{ textAlign: 'center', paddingTop: '4rem' }}>
        <h3 style={{ color: 'var(--error)' }}>{error || 'Test not found'}</h3>
        <Link to="/ui-automation" className="btn btn-secondary mt-4" style={{ textDecoration: 'none' }}>Back to Dashboard</Link>
      </div>
    );
  }

  return (
    <div className="container">
      <div className="mb-4">
        <Link to="/ui-automation" className="flex items-center gap-2" style={{ color: 'var(--text-muted)', textDecoration: 'none', display: 'inline-flex' }}>
          <ArrowLeft size={16} /> Back to Dashboard
        </Link>
      </div>

      <div className="flex items-start justify-between mb-8">
        <div>
          <h1 className="mb-2">{test.name}</h1>
          <div className="flex gap-4 items-center" style={{ color: 'var(--text-muted)' }}>
            <span className="flex items-center gap-1"><MonitorPlay size={16} /> {test.targetUrl}</span>
            <span className="flex items-center gap-1"><Clock size={16} /> Created {new Date(test.createdAt).toLocaleDateString()}</span>
            <span className="flex items-center gap-1"><Activity size={16} /> {test.steps?.length || 0} Steps</span>
          </div>
        </div>

        <div className="flex gap-2">
          <Link to={`/ui-automation/recording/${test.id}`} className="btn btn-secondary" style={{ textDecoration: 'none' }}>
            Re-record
          </Link>
          <button
            className="btn btn-secondary"
            onClick={handleExportScript}
            disabled={!test.steps || test.steps.length === 0}
            title="View & export as Playwright Java script"
          >
            <Code size={18} /> View Script
          </button>
          <button className="btn btn-primary" onClick={handleRunTest} disabled={isStartingRun || !test.steps || test.steps.length === 0}>
            {isStartingRun ? 'Starting Run...' : <><Play size={18} /> Run Test</>}
          </button>
        </div>
      </div>

      {(!test.steps || test.steps.length === 0) && (
        <div className="card mb-8" style={{ backgroundColor: 'rgba(245, 158, 11, 0.1)', borderColor: 'var(--warning)', color: 'var(--warning)' }}>
          <strong>No steps recorded.</strong> Click "Re-record" to launch the browser and capture actions.
        </div>
      )}

      <div className="flex gap-6 mb-6" style={{ borderBottom: '1px solid var(--border-color)' }}>
        <button 
          onClick={() => setActiveTab('steps')}
          style={{ 
            background: 'none', border: 'none', cursor: 'pointer', padding: '0.5rem 0',
            color: activeTab === 'steps' ? 'var(--primary)' : 'var(--text-muted)',
            borderBottom: activeTab === 'steps' ? '2px solid var(--primary)' : '2px solid transparent',
            fontWeight: activeTab === 'steps' ? 600 : 400
          }}>
          Recorded Steps
        </button>
        <button 
          onClick={() => setActiveTab('runs')}
          style={{ 
            background: 'none', border: 'none', cursor: 'pointer', padding: '0.5rem 0',
            color: activeTab === 'runs' ? 'var(--primary)' : 'var(--text-muted)',
            borderBottom: activeTab === 'runs' ? '2px solid var(--primary)' : '2px solid transparent',
            fontWeight: activeTab === 'runs' ? 600 : 400
          }}>
          Execution History
        </button>
        <button 
          onClick={handleExportScript}
          style={{ 
            background: 'none', border: 'none', cursor: 'pointer', padding: '0.5rem 0',
            color: activeTab === 'script' ? 'var(--primary)' : 'var(--text-muted)',
            borderBottom: activeTab === 'script' ? '2px solid var(--primary)' : '2px solid transparent',
            fontWeight: activeTab === 'script' ? 600 : 400
          }}>
          <span style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <Code size={14} /> Test Script
          </span>
        </button>
      </div>

      {/* Tab Content */}
      {activeTab === 'steps' && (
        <div className="card">
          <div className="table-wrapper" style={{ border: 'none' }}>
            <table>
              <thead>
                <tr>
                  <th style={{ width: '60px', textAlign: 'center' }}>#</th>
                  <th style={{ width: '150px' }}>Action</th>
                  <th>Target / Selector</th>
                  <th>Input Value</th>
                </tr>
              </thead>
              <tbody>
                {test.steps?.map((step, index) => (
                  <tr key={step.id || index}>
                    <td style={{ textAlign: 'center', color: 'var(--text-muted)' }}>{step.stepOrder}</td>
                    <td><span className="badge" style={{ backgroundColor: '#334155', color: '#fff' }}>{step.actionType}</span></td>
                    <td style={{ fontFamily: 'monospace', fontSize: '0.85rem', color: 'var(--text-muted)' }}>{step.primarySelector}</td>
                    <td style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}>{step.inputValue || '-'}</td>
                  </tr>
                ))}
                {(!test.steps || test.steps.length === 0) && (
                  <tr><td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>No steps recorded yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {activeTab === 'runs' && (
        <div className="card">
          <div className="table-wrapper" style={{ border: 'none' }}>
            <table>
              <thead>
                <tr>
                  <th>Run ID</th>
                  <th>Status</th>
                  <th>Duration</th>
                  <th>Steps Passed</th>
                  <th>Date</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {runs.map(run => (
                  <tr key={run.id}>
                    <td>#{run.id}</td>
                    <td>
                      <span className={`badge ${run.status === 'PASSED' ? 'badge-success' : run.status === 'FAILED' ? 'badge-error' : 'badge-warning'}`}>
                        {run.status}
                      </span>
                    </td>
                    <td>{(run.totalDurationMs / 1000).toFixed(1)}s</td>
                    <td>{run.passedSteps} / {run.totalSteps}</td>
                    <td style={{ color: 'var(--text-muted)' }}>{new Date(run.startedAt).toLocaleString()}</td>
                    <td style={{ textAlign: 'right' }}>
                      <Link to={`/ui-automation/runs/${run.id}`} className="btn btn-secondary" style={{ textDecoration: 'none', padding: '0.25rem 0.5rem' }}>
                        View Report
                      </Link>
                    </td>
                  </tr>
                ))}
                {runs.length === 0 && (
                  <tr><td colSpan={6} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>No test runs yet.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {activeTab === 'script' && (
        <div className="card" style={{ padding: '1.5rem' }}>
          <div className="flex items-center justify-between mb-4">
            <h3 style={{ margin: 0, fontSize: '1rem' }}>Generated Playwright Java Script</h3>
            <div className="flex gap-2">
              <button
                className="btn btn-secondary"
                onClick={handleCopy}
                style={{ padding: '0.35rem 0.75rem', display: 'flex', alignItems: 'center', gap: '6px' }}
              >
                {copied ? <><Check size={15} /> Copied!</> : <><Copy size={15} /> Copy</>}
              </button>
              <button
                className="btn btn-primary"
                onClick={handleDownloadScript}
                style={{ padding: '0.35rem 0.75rem', display: 'flex', alignItems: 'center', gap: '6px' }}
              >
                <Download size={15} /> Download .java
              </button>
            </div>
          </div>

          {isLoadingScript ? (
            <p style={{ color: 'var(--text-muted)', textAlign: 'center', padding: '2rem' }}>Generating script…</p>
          ) : (
            <pre
              style={{
                background: '#0f172a',
                color: '#e2e8f0',
                padding: '1.25rem',
                borderRadius: '8px',
                fontSize: '0.8rem',
                lineHeight: '1.6',
                overflowX: 'auto',
                whiteSpace: 'pre',
                maxHeight: '60vh',
                overflowY: 'auto',
                margin: 0,
                fontFamily: '"JetBrains Mono", "Fira Code", Consolas, monospace',
              }}
            >
              {scriptContent}
            </pre>
          )}
        </div>
      )}

    </div>
  );
}
