import { Link } from 'react-router-dom';
import { MousePointerClick, Code2, Gauge, Accessibility, ShieldCheck } from 'lucide-react';

export default function Dashboard() {
  return (
    <div className="container">
      <div className="flex items-center justify-between mb-8">
        <div>
          <h1>Testing Dashboard</h1>
          <p>Select a testing capability to begin.</p>
        </div>
      </div>

      <div className="flex gap-6" style={{ flexWrap: 'wrap' }}>
        {/* UI Automation Testing Card */}
        <div className="card" style={{ flex: '1 1 300px' }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <MousePointerClick size={24} className="text-blue-500" />
              <h3>UI Automation Testing</h3>
            </div>
            <span className="badge badge-success">Available</span>
          </div>
          <p className="mb-6">Record, run, and manage browser-based UI automation tests.</p>
          <Link to="/ui-automation" className="btn btn-primary" style={{ textDecoration: 'none' }}>
            Open UI Automation
          </Link>
        </div>

        {/* API Testing */}
        <div className="card" style={{ flex: '1 1 300px', opacity: 0.7 }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Code2 size={24} className="text-gray-400" />
              <h3>API Testing</h3>
            </div>
            <span className="badge" style={{ backgroundColor: '#334155', color: '#cbd5e1' }}>Coming Soon</span>
          </div>
          <p className="mb-6">Validate REST and GraphQL endpoints.</p>
          <button className="btn btn-secondary" disabled>Not Available</button>
        </div>

        {/* Performance Testing */}
        <div className="card" style={{ flex: '1 1 300px', opacity: 0.7 }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Gauge size={24} className="text-gray-400" />
              <h3>Performance Testing</h3>
            </div>
            <span className="badge" style={{ backgroundColor: '#334155', color: '#cbd5e1' }}>Coming Soon</span>
          </div>
          <p className="mb-6">Load test your application architecture.</p>
          <button className="btn btn-secondary" disabled>Not Available</button>
        </div>

        {/* Accessibility Testing */}
        <div className="card" style={{ flex: '1 1 300px', opacity: 0.7 }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <Accessibility size={24} className="text-gray-400" />
              <h3>Accessibility</h3>
            </div>
            <span className="badge" style={{ backgroundColor: '#334155', color: '#cbd5e1' }}>Coming Soon</span>
          </div>
          <p className="mb-6">Ensure your application is usable by everyone.</p>
          <button className="btn btn-secondary" disabled>Not Available</button>
        </div>

        {/* Security Testing */}
        <div className="card" style={{ flex: '1 1 300px', opacity: 0.7 }}>
          <div className="flex items-center justify-between mb-4">
            <div className="flex items-center gap-2">
              <ShieldCheck size={24} className="text-gray-400" />
              <h3>Security Testing</h3>
            </div>
            <span className="badge" style={{ backgroundColor: '#334155', color: '#cbd5e1' }}>Coming Soon</span>
          </div>
          <p className="mb-6">Scan for vulnerabilities and misconfigurations.</p>
          <button className="btn btn-secondary" disabled>Not Available</button>
        </div>
      </div>
    </div>
  );
}
