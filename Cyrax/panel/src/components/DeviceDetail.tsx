import React, { useEffect, useState } from 'react'
import { useParams, Link } from 'react-router-dom'
import { devices as devApi } from '../api/client'
import type { Device } from '../api/types'

export default function DeviceDetail() {
  const { id } = useParams<{ id: string }>()
  const [dev, setDev] = useState<Device | null>(null)

  useEffect(() => {
    if (id) devApi.get(id).then(r => setDev(r.data))
  }, [id])

  if (!dev) return <div className="p-6 text-gray-400">Loading…</div>

  const modules = [
    { label: 'SMS', href: 'sms' },
    { label: 'Keylogs', href: 'keylogs' },
    { label: 'Location', href: 'location' },
    { label: 'Files', href: 'files' },
    { label: 'Commands', href: 'commands' }
  ]

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-2">{dev.model}</h1>
      <p className="text-gray-400 mb-6">{dev.id}</p>
      <div className="grid grid-cols-2 gap-4 mb-6">
        {[
          ['Android', dev.android_version],
          ['Battery', `${dev.battery}%`],
          ['IP', dev.ip],
          ['Country', dev.country],
          ['Root', dev.is_rooted ? 'Yes' : 'No'],
          ['A11Y', dev.accessibility ? 'Active' : 'Inactive']
        ].map(([k, v]) => (
          <div key={k as string} className="bg-panel border border-border rounded p-3">
            <span className="text-gray-400 text-sm">{k}</span>
            <span className="text-white ml-2">{v}</span>
          </div>
        ))}
      </div>
      <div className="grid grid-cols-5 gap-3">
        {modules.map(m => (
          <Link key={m.label} to={`/devices/${id}/${m.href}`}
            className="bg-accent hover:bg-purple-500 text-white text-center py-3 rounded transition">
            {m.label}
          </Link>
        ))}
      </div>
    </div>
  )
}
