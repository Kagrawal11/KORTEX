import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Plus, RefreshCw, FileText } from 'lucide-react';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestScenario } from '../types';
import CreateTestDialog from '../components/CreateTestDialog';

export default function UIAutomationDashboard() {
  const [tests, setTests] = useState<TestScenario[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState('');
  const [isCreateOpen, setIsCreateOpen] = useState(false);

  const fetchTests = async () => {
    try {
      setIsLoading(true);
      setError('');
      const data = await uiAutomationApi.getTests();
      setTests(data);
    } catch (err) {
      setError('We couldn\'t retrieve your UI automation tests.');
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchTests();
  }, []);

  return (
    <div className="container">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1>UI Automation Testing</h1>
          <p>Create, record, execute, and monitor browser automation tests.</p>
        </div>
        <div className="flex items-center gap-4">
          <button className="btn btn-secondary" onClick={fetchTests} disabled={isLoading}>
            <RefreshCw size={18} className={isLoading ? 'animate-spin' : ''} />
            Refresh
          </button>
          <button className="btn btn-primary" onClick={() => setIsCreateOpen(true)}>
            <Plus size={18} />
            Create Test
          </button>
        </div>
      </div>

      {error ? (
        <div className="card" style={{ textAlign: 'center', padding: '4rem 2rem' }}>
          <h3 className="mb-2" style={{ color: 'var(--error)' }}>Unable to load tests</h3>
          <p className="mb-6">{error}</p>
          <div className="flex justify-center gap-4">
            <button className="btn btn-primary" onClick={fetchTests}>Retry</button>
            <Link to="/" className="btn btn-secondary" style={{ textDecoration: 'none' }}>Go Back</Link>
          </div>
        </div>
      ) : isLoading ? (
        <div className="card" style={{ textAlign: 'center', padding: '4rem 2rem' }}>
          <RefreshCw size={32} className="animate-spin text-blue-500 mx-auto mb-4" style={{ animation: 'spin 1s linear infinite' }} />
          <p>Loading your test history...</p>
        </div>
      ) : tests.length === 0 ? (
        <div className="card" style={{ textAlign: 'center', padding: '4rem 2rem' }}>
          <h2 className="mb-2">No UI Tests Yet</h2>
          <p className="mb-6">Create your first UI automation test by recording interactions with your website.</p>
          <button className="btn btn-primary mx-auto" onClick={() => setIsCreateOpen(true)}>
            <Plus size={18} /> Create UI Test
          </button>
        </div>
      ) : (
        <>
          <div className="flex gap-4 mb-6">
            <div className="card" style={{ flex: 1, padding: '1rem' }}>
              <p className="mb-1">Total Tests</p>
              <h3>{tests.length}</h3>
            </div>
            {/* Adding mock metrics for demonstration as requested by the plan */}
            <div className="card" style={{ flex: 1, padding: '1rem' }}>
              <p className="mb-1">Passed (Last Run)</p>
              <h3 style={{ color: 'var(--success)' }}>--</h3>
            </div>
            <div className="card" style={{ flex: 1, padding: '1rem' }}>
              <p className="mb-1">Failed (Last Run)</p>
              <h3 style={{ color: 'var(--error)' }}>--</h3>
            </div>
          </div>

          <div className="table-wrapper">
            <table>
              <thead>
                <tr>
                  <th>Test Name</th>
                  <th>Project</th>
                  <th>Website</th>
                  <th>Status</th>
                  <th>Steps</th>
                  <th>Created</th>
                  <th style={{ textAlign: 'right' }}>Actions</th>
                </tr>
              </thead>
              <tbody>
                {tests.map(test => (
                  <tr key={test.id}>
                    <td>
                      <Link to={`/ui-automation/tests/${test.id}`} style={{ color: 'var(--primary)', textDecoration: 'none', fontWeight: 500 }}>
                        {test.name}
                      </Link>
                    </td>
                    <td>Demo App</td>
                    <td style={{ color: 'var(--text-muted)' }}>{test.targetUrl}</td>
                    <td>
                      {test.steps && test.steps.length > 0 ? (
                        <span className="badge badge-success">Recorded</span>
                      ) : (
                        <span className="badge" style={{ backgroundColor: '#334155', color: '#cbd5e1' }}>Draft</span>
                      )}
                    </td>
                    <td>{test.steps ? test.steps.length : 0}</td>
                    <td style={{ color: 'var(--text-muted)' }}>
                      {new Date(test.createdAt).toLocaleDateString()}
                    </td>
                    <td style={{ textAlign: 'right' }}>
                      <div className="flex justify-end gap-2">
                        <Link to={`/ui-automation/tests/${test.id}`} className="btn btn-secondary" style={{ padding: '0.25rem 0.5rem' }}>
                          <FileText size={16} />
                        </Link>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      {isCreateOpen && (
        <CreateTestDialog onClose={() => setIsCreateOpen(false)} />
      )}
    </div>
  );
}
