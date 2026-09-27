import React, { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { devices as devApi, campaigns } from '../api/client'
import { realtime } from '../api/websocket'
import type { Device } from '../api/types'

export default function Dashboard() {
  const [devCount, setDevCount] = useState(0)
  const [online, setOnline] = useState(0)
  const [camCount, setCamCount] = useState(0)

  useEffect(() => {
    devApi.list(0, 1).then(r => setDevCount(r.data.total ?? 0))
    campaigns.list().then(r => setCamCount(r.data.length ?? 0))
    const h = (d: unknown) => {
      const data = d as { online: number }
      setOnline(data.online)
    }
    realtime.on('stats', h)
    return () => realtime.off('stats', h)
  }, [])

  const cards = [
    { label: 'Total Devices', value: devCount, href: '/devices' },
    { label: 'Online Now', value: online, href: '/devices' },
    { label: 'Campaigns', value: camCount, href: '/stats' }
  ]

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-6">Dashboard</h1>
      <div className="grid grid-cols-3 gap-4">
        {cards.map(c => (
          <Link key={c.label} to={c.href}
            className="bg-panel border border-border rounded-lg p-6 hover:border-accent transition">
            <div className="text-gray-400 text-sm">{c.label}</div>
            <div className="text-white text-3xl font-bold mt-1">{c.value}</div>
          </Link>
        ))}
      </div>
    </div>
  )
}
