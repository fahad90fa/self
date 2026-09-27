import React, { useEffect, useState } from 'react'
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts'
import { campaigns } from '../api/client'
import type { Campaign } from '../api/types'

export default function Stats() {
  const [cams, setCams] = useState<Campaign[]>([])

  useEffect(() => {
    campaigns.list().then(r => setCams(r.data ?? []))
  }, [])

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-6">Statistics</h1>
      <div className="bg-panel border border-border rounded-lg p-4 mb-6">
        <h2 className="text-white font-medium mb-4">Devices per Campaign</h2>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart data={cams}>
            <XAxis dataKey="name" stroke="#6b7280" />
            <YAxis stroke="#6b7280" />
            <Tooltip contentStyle={{ background: '#1a1d27', border: '1px solid #2d3048' }} />
            <Bar dataKey="device_count" fill="#7c3aed" />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <div className="grid grid-cols-2 gap-4">
        {cams.map(c => (
          <div key={c.id} className="bg-panel border border-border rounded-lg p-4">
            <div className="text-white font-medium">{c.name}</div>
            <div className="text-gray-400 text-sm mt-1">{c.target_country} · {c.device_count} devices</div>
            <div className="text-gray-500 text-xs mt-1">{new Date(c.created_at).toLocaleDateString()}</div>
          </div>
        ))}
      </div>
    </div>
  )
}
