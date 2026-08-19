import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';
import Dashboard from './pages/Dashboard';
import UIAutomationDashboard from './pages/UIAutomationDashboard';
import RecordingWorkspace from './pages/RecordingWorkspace';
import TestDetails from './pages/TestDetails';
import TestReport from './pages/TestReport';
import { Activity } from 'lucide-react';

function App() {
  return (
    <Router>
      <div className="flex flex-col min-h-screen">
        <header className="border-b border-[var(--border-color)] bg-[var(--bg-panel)] p-4">
          <div className="container mx-auto flex items-center gap-2">
            <Activity className="text-blue-500" />
            <Link to="/" className="text-xl font-bold text-white no-underline">
              Mini Automation
            </Link>
          </div>
        </header>
        
        <main className="flex-1">
          <Routes>
            <Route path="/" element={<Dashboard />} />
            <Route path="/ui-automation" element={<UIAutomationDashboard />} />
            <Route path="/ui-automation/recording/:testId" element={<RecordingWorkspace />} />
            <Route path="/ui-automation/tests/:testId" element={<TestDetails />} />
            <Route path="/ui-automation/runs/:runId" element={<TestReport />} />
          </Routes>
        </main>
      </div>
    </Router>
  );
}

export default App;
