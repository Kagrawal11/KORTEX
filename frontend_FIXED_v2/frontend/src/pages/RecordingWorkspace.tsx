import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { uiAutomationApi } from '../services/uiAutomationApi';
import type { TestScenario } from '../types';
import { PlayCircle, Square, ArrowLeft } from 'lucide-react';

export default function RecordingWorkspace() {
  const { testId } = useParams();
  const navigate = useNavigate();
  
  const [test, setTest] = useState<TestScenario | null>(null);
  const [isRecording, setIsRecording] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState('');
  const [recordingTimer, setRecordingTimer] = useState(0);

  useEffect(() => {
    if (testId) {
      uiAutomationApi.getTest(Number(testId))
        .then(setTest)
        .catch(() => setError('Failed to load test scenario.'));
    }
  }, [testId]);

  useEffect(() => {
    let interval: number;
    if (isRecording) {
      interval = window.setInterval(() => {
        setRecordingTimer(prev => prev + 1);
      }, 1000);
    }
    return () => clearInterval(interval);
  }, [isRecording]);

  const handleStartRecording = async () => {
    try {
      setError('');
      await uiAutomationApi.startRecording(Number(testId));
      setIsRecording(true);
    } catch (err: any) {
      setError('Failed to start recording. Ensure Playwright is configured correctly.');
    }
  };

  const handleStopRecording = async () => {
    try {
      setIsSaving(true);
      setError('');
      // This stops recording and persists the captured steps. The backend keeps the browser session alive.
      const updatedTest = await uiAutomationApi.stopRecording(Number(testId));
      setIsRecording(false);
      setRecordingTimer(0);
      setTest(updatedTest);
      // Once stopped and saved, go to Test Details
      navigate(`/ui-automation/tests/${testId}`);
    } catch (err: any) {
      setError('Failed to stop and save recording.');
      setIsSaving(false);
    }
  };

  const formatTime = (seconds: number) => {
    const m = Math.floor(seconds / 60).toString().padStart(2, '0');
    const s = (seconds % 60).toString().padStart(2, '0');
    return `${m}:${s}`;
  };

  if (!test) {
    return (
      <div className="container" style={{ textAlign: 'center', paddingTop: '4rem' }}>
        {error ? <p style={{ color: 'var(--error)' }}>{error}</p> : <p>Loading workspace...</p>}
      </div>
    );
  }

  return (
    <div style={{ height: 'calc(100vh - 65px)', display: 'flex', flexDirection: 'column' }}>
      {/* Header Bar */}
      <div className="flex items-center justify-between" style={{ padding: '1rem 2rem', backgroundColor: 'var(--bg-panel)', borderBottom: '1px solid var(--border-color)' }}>
        <div>
          <h2 style={{ fontSize: '1.125rem' }}>{test.name}</h2>
          <p style={{ fontSize: '0.875rem' }}>{test.targetUrl}</p>
        </div>
        
        <div className="flex items-center gap-6">
          {isRecording ? (
            <div className="flex items-center gap-2" style={{ color: 'var(--error)' }}>
              <div style={{ width: '10px', height: '10px', borderRadius: '50%', backgroundColor: 'var(--error)', animation: 'pulse 1.5s infinite' }}></div>
              <span style={{ fontWeight: 600, fontFamily: 'monospace', fontSize: '1.125rem' }}>
                Recording {formatTime(recordingTimer)}
              </span>
            </div>
          ) : (
            <div className="flex items-center gap-2" style={{ color: 'var(--text-muted)' }}>
              <div style={{ width: '10px', height: '10px', borderRadius: '50%', backgroundColor: 'var(--text-muted)' }}></div>
              <span style={{ fontWeight: 600 }}>Not Recording</span>
            </div>
          )}

          <div className="flex items-center gap-2">
            {!isRecording ? (
              <button className="btn btn-primary" onClick={handleStartRecording} disabled={isSaving}>
                <PlayCircle size={18} /> Start Recording
              </button>
            ) : (
              <button className="btn btn-danger" onClick={handleStopRecording} disabled={isSaving}>
                {isSaving ? 'Saving...' : <><Square size={18} /> Stop & Save</>}
              </button>
            )}
          </div>
        </div>
      </div>

      {error && (
        <div style={{ padding: '1rem 2rem', backgroundColor: 'var(--error-bg)', color: 'var(--error)' }}>
          {error}
        </div>
      )}

      {/* Main Workspace Area */}
      <div className="flex flex-1" style={{ overflow: 'hidden' }}>
        
        {/* Left/Main Area (Instructions / Status) */}
        <div className="flex-1" style={{ padding: '2rem', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', borderRight: '1px solid var(--border-color)' }}>
          {isRecording ? (
            <div className="card" style={{ maxWidth: '600px', textAlign: 'center' }}>
              <h2 className="mb-4">Browser is Open</h2>
              <p className="mb-4" style={{ fontSize: '1.125rem' }}>
                A Chromium browser has been launched by the automation engine.
              </p>
              <div style={{ backgroundColor: 'rgba(0,0,0,0.2)', padding: '1.5rem', borderRadius: 'var(--radius-md)', border: '1px dashed var(--border-color)' }}>
                <p>1. Switch to the popup browser window.</p>
                <p>2. Interact with the website normally.</p>
                <p>3. Every click and type is being captured by the backend.</p>
                <p>4. Return here and click "Stop & Save" when finished.</p>
              </div>
            </div>
          ) : (
            <div className="card" style={{ maxWidth: '600px', textAlign: 'center' }}>
              <h2 className="mb-4">Ready to Record</h2>
              <p className="mb-6">Click "Start Recording" to launch the browser and begin capturing your interactions.</p>
              <button className="btn btn-secondary" onClick={() => navigate('/ui-automation')}>
                <ArrowLeft size={18} /> Back to Dashboard
              </button>
            </div>
          )}
        </div>

        {/* Right Sidebar (Live Steps Panel - Placeholder for future live updates) */}
        <div style={{ width: '350px', backgroundColor: 'var(--bg-panel)', display: 'flex', flexDirection: 'column' }}>
          <div style={{ padding: '1rem', borderBottom: '1px solid var(--border-color)' }}>
            <h3 style={{ fontSize: '1rem' }}>Recorded Steps</h3>
          </div>
          <div style={{ padding: '1rem', flex: 1, overflowY: 'auto' }}>
            {isRecording ? (
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: '2rem', color: 'var(--text-muted)' }}>
                <div className="animate-pulse mb-4" style={{ width: '40px', height: '40px', borderRadius: '50%', backgroundColor: 'rgba(59, 130, 246, 0.2)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <div style={{ width: '20px', height: '20px', borderRadius: '50%', backgroundColor: 'var(--primary)' }}></div>
                </div>
                <p>Listening for actions...</p>
                <p style={{ fontSize: '0.75rem', marginTop: '0.5rem', textAlign: 'center' }}>
                  Steps will be displayed here once saved, as real-time WS streaming is not yet enabled.
                </p>
              </div>
            ) : (
              <p style={{ color: 'var(--text-muted)', textAlign: 'center', paddingTop: '2rem' }}>
                No steps recorded yet.
              </p>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}
