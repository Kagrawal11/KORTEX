import { useEffect, useState, useRef } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { uiAutomationApi, RECORDING_VNC_URL } from '../services/uiAutomationApi';
import type { TestScenario } from '../types';
import { PlayCircle, Square, ArrowLeft, Radio, Globe2, Monitor, ListChecks, RefreshCw, Lock } from 'lucide-react';
import { useToast } from '../components/Toast';

export default function RecordingWorkspace() {
  const { testId } = useParams();
  const navigate = useNavigate();

  const [test, setTest] = useState<TestScenario | null>(null);
  const [isRecording, setIsRecording] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState('');
  const [recordingTimer, setRecordingTimer] = useState(0);
  const { showToast } = useToast();

  // Live browser — an interactive noVNC view of the real Playwright Chromium
  // running on the server's virtual display. Clicks and keystrokes made here
  // are delivered as real X input, so Chromium raises genuinely trusted DOM
  // events and RecordingSession's injected listeners capture them exactly as
  // they would from a local browser window. Nothing in the recording control
  // flow is touched by this component.
  const [previewLoaded, setPreviewLoaded] = useState(false);
  const [currentUrl, setCurrentUrl] = useState('');

  // Ref-based guard prevents a second stop request even before React re-renders
  // isSaving — a synchronous check that catches rapid double-clicks.
  const stopInFlight = useRef(false);

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

  useEffect(() => {
    if (!isRecording) {
      setPreviewLoaded(false);
      return;
    }
    // Only the URL bar polls now. The browser view itself is a live VNC
    // stream, so there is no screenshot poll to run.
    const statusInterval = window.setInterval(() => {
      uiAutomationApi.getRecordingStatus()
        .then(status => setCurrentUrl(status.currentUrl))
        .catch(() => { /* transient — keep showing the last known URL */ });
    }, 2000);
    return () => clearInterval(statusInterval);
  }, [isRecording]);

  const handleStartRecording = async () => {
    try {
      setError('');
      stopInFlight.current = false; // reset guard for the new session
      await uiAutomationApi.startRecording(Number(testId));
      setIsRecording(true);
      setPreviewLoaded(false);
      setCurrentUrl(test?.targetUrl ?? '');
      showToast('Recording started — interact with the browser below.', 'success');
    } catch (err: any) {
      // Show the actual server-side reason (e.g. "a playback is currently running")
      const serverMsg = err?.response?.data?.error || err?.response?.data?.message;
      if (serverMsg) {
        setError(`Cannot start recording: ${serverMsg}`);
      } else {
        setError('Failed to start recording. Make sure no test run is currently active, then try again.');
      }
    }
  };

  const handleStopRecording = async () => {
    // Synchronous ref guard: fires before any re-render, blocks double-clicks
    // that isSaving (which needs a re-render cycle) would not catch in time.
    if (stopInFlight.current) return;
    stopInFlight.current = true;

    try {
      setIsSaving(true);
      setError('');
      // Stop recording and persist the captured steps.
      const updatedTest = await uiAutomationApi.stopRecording(Number(testId));
      // Update state BEFORE navigating. Calling navigate() first unmounts
      // this component while the HTTP response is still being written,
      // which causes AsyncRequestNotUsableException on the backend and
      // triggers an apparent second stop request from the user.
      setIsRecording(false);
      setRecordingTimer(0);
      setTest(updatedTest);
      showToast(`Recording saved — ${updatedTest.steps?.length ?? 0} step(s) captured.`, 'success');
      navigate(`/ui-automation/tests/${testId}`);
    } catch (err: any) {
      setError('Failed to stop and save recording.');
      setIsSaving(false);
      stopInFlight.current = false; // allow retry on genuine network error
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
        {error ? <p className="text-error">{error}</p> : <p>Loading workspace...</p>}
      </div>
    );
  }

  return (
    <div style={{ height: '100%', minHeight: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* Header Bar */}
      <div className="flex items-center justify-between" style={{ padding: '1rem 2rem', backgroundColor: 'var(--bg-panel)', borderBottom: '1px solid var(--border-color)' }}>
        <div>
          <div className="text-xs text-muted mb-1">Recording Workspace</div>
          <h2 style={{ fontSize: '1.125rem', margin: 0 }}>{test.name}</h2>
          <p style={{ fontSize: '0.85rem', margin: 0 }}>{test.targetUrl}</p>
        </div>

        <div className="flex items-center gap-6">
          {isRecording ? (
            <div className="flex items-center gap-2" style={{ color: 'var(--error)' }}>
              <span className="status-dot pulse" style={{ backgroundColor: 'var(--error)' }} />
              <Radio size={16} />
              <span className="font-mono" style={{ fontWeight: 600, fontSize: '1.125rem' }}>
                Recording {formatTime(recordingTimer)}
              </span>
            </div>
          ) : (
            <div className="flex items-center gap-2 text-muted">
              <span className="status-dot" style={{ backgroundColor: 'var(--text-subtle)' }} />
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
                {isSaving ? <><RefreshCw size={18} className="animate-spin" /> Saving...</> : <><Square size={18} /> Stop &amp; Save</>}
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

        {/* Left/Main Area — live browser preview while recording */}
        <div className="flex-1" style={{ padding: '2rem', display: 'flex', flexDirection: 'column', alignItems: 'center', borderRight: '1px solid var(--border-color)', overflowY: 'auto' }}>
          {isRecording ? (
            <div className="card" style={{ maxWidth: '1280px', width: '100%', padding: 0, overflow: 'hidden' }}>
              {/* Mock browser chrome — matches the popup window's real current URL */}
              <div className="flex items-center gap-2" style={{ padding: '0.6rem 0.85rem', background: 'var(--bg-elevated)', borderBottom: '1px solid var(--border-color)' }}>
                <span style={{ width: 10, height: 10, borderRadius: '50%', background: '#EF4444' }} />
                <span style={{ width: 10, height: 10, borderRadius: '50%', background: '#F59E0B' }} />
                <span style={{ width: 10, height: 10, borderRadius: '50%', background: '#10B981' }} />
                <div className="flex items-center gap-1 text-xs text-muted truncate" style={{ marginLeft: '0.5rem', flex: 1, background: 'var(--bg-input)', padding: '0.3rem 0.6rem', borderRadius: 'var(--radius-sm)' }}>
                  <Lock size={11} />
                  <span className="truncate">{currentUrl || test.targetUrl}</span>
                </div>
              </div>
              {/* The iframe stays mounted while loading rather than being
                  display:none'd — noVNC measures its container on connect and
                  a hidden element has no dimensions to measure. The spinner is
                  an overlay on top of it instead. */}
              <div style={{ position: 'relative', background: 'var(--bg-input)', aspectRatio: '1280 / 900' }}>
                <iframe
                  key="recording-vnc"
                  src={RECORDING_VNC_URL}
                  onLoad={() => setPreviewLoaded(true)}
                  title="Live browser session"
                  style={{ width: '100%', height: '100%', border: 'none', display: 'block' }}
                />
                {!previewLoaded && (
                  <div
                    className="flex flex-col items-center justify-center text-muted"
                    style={{ position: 'absolute', inset: 0, background: 'var(--bg-input)', textAlign: 'center', padding: '1rem' }}
                  >
                    <RefreshCw size={22} className="animate-spin mb-3" />
                    <p className="text-sm" style={{ margin: 0 }}>Connecting to the live browser…</p>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div className="card" style={{ maxWidth: '600px', width: '100%', textAlign: 'center', marginTop: 'auto', marginBottom: 'auto' }}>
              <h2 className="mb-4">Ready to Record</h2>
              <p className="mb-6">Click "Start Recording" to launch the browser and begin capturing your interactions.</p>
              <button className="btn btn-secondary" onClick={() => navigate('/ui-automation')}>
                <ArrowLeft size={18} /> Back to Dashboard
              </button>
            </div>
          )}

          {isRecording && (
            <div className="text-sm mt-4" style={{ maxWidth: '1280px', width: '100%', color: 'var(--text-muted)' }}>
              Click and type directly in the browser above — it is the real Chromium session, and every
              interaction is captured by the backend. Click "Stop &amp; Save" when you are finished.
            </div>
          )}

          {/* Session info — static, known-true facts about this recording
              session (target URL, engine), not a live telemetry feed. */}
          <div className="flex gap-4 mt-6 flex-wrap" style={{ maxWidth: '1280px', width: '100%' }}>
            <div className="card" style={{ flex: '1 1 200px', padding: '0.9rem 1.1rem' }}>
              <div className="flex items-center gap-2 text-xs text-muted mb-1"><Globe2 size={14} /> Target Website</div>
              <div className="text-sm font-medium truncate">{test.targetUrl}</div>
            </div>
            <div className="card" style={{ flex: '1 1 200px', padding: '0.9rem 1.1rem' }}>
              <div className="flex items-center gap-2 text-xs text-muted mb-1"><Monitor size={14} /> Browser Engine</div>
              <div className="text-sm font-medium">Chromium (Playwright)</div>
            </div>
          </div>
        </div>

        {/* Right Sidebar (Live Steps Panel - Placeholder for future live updates) */}
        <div style={{ width: '350px', backgroundColor: 'var(--bg-panel)', display: 'flex', flexDirection: 'column' }}>
          <div className="border-b flex items-center gap-2" style={{ padding: '1rem' }}>
            <ListChecks size={16} className="text-primary" />
            <h3 style={{ fontSize: '1rem', margin: 0 }}>Recorded Steps</h3>
          </div>
          <div style={{ padding: '1rem', flex: 1, overflowY: 'auto' }}>
            {isRecording ? (
              <div className="flex flex-col items-center text-muted" style={{ paddingTop: '2rem', textAlign: 'center' }}>
                <div className="animate-pulse mb-4" style={{ width: '44px', height: '44px', borderRadius: '50%', backgroundColor: 'var(--primary-bg)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                  <div style={{ width: '18px', height: '18px', borderRadius: '50%', backgroundColor: 'var(--primary)' }}></div>
                </div>
                <p>Listening for actions...</p>
                <p className="text-xs mt-4" style={{ textAlign: 'center' }}>
                  Steps will be displayed here once saved, as real-time WS streaming is not yet enabled.
                </p>
              </div>
            ) : (
              <p className="text-muted" style={{ textAlign: 'center', paddingTop: '2rem' }}>
                No steps recorded yet.
              </p>
            )}
          </div>
        </div>

      </div>
    </div>
  );
}
