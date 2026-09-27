import React, { useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Toaster } from 'react-hot-toast'
import Login from './auth/Login'
import Dashboard from './components/Dashboard'
import DeviceList from './components/DeviceList'
import DeviceDetail from './components/DeviceDetail'
import CommandCenter from './components/CommandCenter'
import SmsViewer from './components/SmsViewer'
import KeylogViewer from './components/KeylogViewer'
import LocationMap from './components/LocationMap'
import FileBrowser from './components/FileBrowser'
import Stats from './components/Stats'
import Settings from './components/Settings'
import { realtime } from './api/websocket'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const token = localStorage.getItem('token')
  if (!token) return <Navigate to="/login" replace />
  return <>{children}</>
}

export default function App() {
  useEffect(() => {
    if (localStorage.getItem('token')) realtime.connect()
    return () => realtime.disconnect()
  }, [])

  return (
    <BrowserRouter>
      <Toaster position="bottom-right" toastOptions={{ style: { background: '#1a1d27', color: '#fff' } }} />
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/" element={<RequireAuth><Dashboard /></RequireAuth>} />
        <Route path="/devices" element={<RequireAuth><DeviceList /></RequireAuth>} />
        <Route path="/devices/:id" element={<RequireAuth><DeviceDetail /></RequireAuth>} />
        <Route path="/devices/:id/commands" element={<RequireAuth><CommandCenter /></RequireAuth>} />
        <Route path="/devices/:id/sms" element={<RequireAuth><SmsViewer /></RequireAuth>} />
        <Route path="/devices/:id/keylogs" element={<RequireAuth><KeylogViewer /></RequireAuth>} />
        <Route path="/devices/:id/location" element={<RequireAuth><LocationMap /></RequireAuth>} />
        <Route path="/devices/:id/files" element={<RequireAuth><FileBrowser /></RequireAuth>} />
        <Route path="/stats" element={<RequireAuth><Stats /></RequireAuth>} />
        <Route path="/settings" element={<RequireAuth><Settings /></RequireAuth>} />
      </Routes>
    </BrowserRouter>
  )
}
