import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import UIAutomationDashboard from './pages/UIAutomationDashboard';
import RecordingWorkspace from './pages/RecordingWorkspace';
import TestDetails from './pages/TestDetails';
import TestReport from './pages/TestReport';
import DataDrivenReport from './pages/DataDrivenReport';
import DataDrivenDashboard from './pages/DataDrivenDashboard';
import NewDataDrivenTest from './pages/NewDataDrivenTest';
import ExecutionHistory from './pages/ExecutionHistory';
import AccessibilityDashboard from './pages/AccessibilityDashboard';
import NewAccessibilityScan from './pages/NewAccessibilityScan';
import AccessibilityReport from './pages/AccessibilityReport';
import AccessibilityScanTrend from './pages/AccessibilityScanTrend';
import ApiTestingDashboard from './pages/ApiTestingDashboard';
import ApiEnvironmentManager from './pages/ApiEnvironmentManager';
import ApiPlayground from './pages/ApiPlayground';
import ApiWorkspace from './pages/ApiWorkspace';
import ApiRunReport from './pages/ApiRunReport';
import Settings from './pages/Settings';
import { ToastProvider } from './components/Toast';
import Sidebar from './components/Sidebar';
import Topbar from './components/Topbar';

function App() {
  return (
    <ToastProvider>
      <Router>
        <div className="app-shell">
          <Sidebar />
          <div className="app-shell-body">
            <Topbar />
            <main className="app-shell-main">
              <Routes>
                <Route path="/" element={<Dashboard />} />
                <Route path="/ui-automation" element={<UIAutomationDashboard />} />
                <Route path="/ui-automation/recording/:testId" element={<RecordingWorkspace />} />
                <Route path="/ui-automation/tests/:testId" element={<TestDetails />} />
                <Route path="/ui-automation/runs/:runId" element={<TestReport />} />
                <Route path="/ui-automation/data-driven/runs/:runId" element={<DataDrivenReport />} />
                <Route path="/data-driven" element={<DataDrivenDashboard />} />
                <Route path="/data-driven/new" element={<NewDataDrivenTest />} />
                <Route path="/execution-history" element={<ExecutionHistory />} />
                <Route path="/accessibility" element={<AccessibilityDashboard />} />
                <Route path="/accessibility/new" element={<NewAccessibilityScan />} />
                <Route path="/accessibility/runs/:runId" element={<AccessibilityReport />} />
                <Route path="/accessibility/scans/:scanId/trend" element={<AccessibilityScanTrend />} />
                <Route path="/api-testing" element={<ApiTestingDashboard />} />
                <Route path="/api-testing/new" element={<ApiPlayground />} />
                <Route path="/api-testing/environments" element={<ApiEnvironmentManager />} />
                <Route path="/api-testing/collections/:collectionId" element={<ApiWorkspace />} />
                <Route path="/api-testing/runs/:runId" element={<ApiRunReport />} />
                <Route path="/settings" element={<Settings />} />
              </Routes>
            </main>
          </div>
        </div>
      </Router>
    </ToastProvider>
  );
}

export default App;
