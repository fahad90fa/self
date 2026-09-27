import React, { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { devices as devApi } from '../api/client'
import type { Device } from '../api/types'

export default function DeviceList() {
  const [list, setList] = useState<Device[]>([])
  const [search, setSearch] = useState('')

  useEffect(() => {
    devApi.list().then(r => setList(r.data.devices ?? []))
  }, [])

  const filtered = list.filter(d =>
    d.model.toLowerCase().includes(search.toLowerCase()) ||
    d.id.includes(search) ||
    d.campaign_id.includes(search)
  )

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-4">Devices</h1>
      <input
        className="bg-panel border border-border text-white px-3 py-2 rounded w-full mb-4"
        placeholder="Search..." value={search} onChange={e => setSearch(e.target.value)}
      />
      <div className="space-y-2">
        {filtered.map(d => (
          <Link key={d.id} to={`/devices/${d.id}`}
            className="block bg-panel border border-border rounded-lg p-4 hover:border-accent transition">
            <div className="flex justify-between items-center">
              <div>
                <span className="text-white font-medium">{d.model}</span>
                <span className="text-gray-400 text-sm ml-3">{d.id.slice(0, 12)}…</span>
              </div>
              <div className="flex items-center gap-4">
                <span className="text-gray-400 text-sm">🔋 {d.battery}%</span>
                <span className="text-gray-400 text-sm">{d.country}</span>
                <span className={`text-xs px-2 py-1 rounded ${d.accessibility ? 'bg-ok text-black' : 'bg-danger text-white'}`}>
                  {d.accessibility ? 'A11Y' : 'NO A11Y'}
                </span>
              </div>
            </div>
          </Link>
        ))}
      </div>
    </div>
  )
}
