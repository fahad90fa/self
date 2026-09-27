import React, { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { sms } from '../api/client'
import type { SmsMessage } from '../api/types'

export default function SmsViewer() {
  const { id } = useParams<{ id: string }>()
  const [msgs, setMsgs] = useState<SmsMessage[]>([])
  const [search, setSearch] = useState('')

  useEffect(() => {
    if (id) sms.list(id).then(r => setMsgs(r.data.messages ?? []))
  }, [id])

  const filtered = msgs.filter(m =>
    m.body.toLowerCase().includes(search.toLowerCase()) ||
    m.address.includes(search)
  )

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-4">SMS — {msgs.length} messages</h1>
      <input className="bg-panel border border-border text-white px-3 py-2 rounded w-full mb-4"
        placeholder="Search…" value={search} onChange={e => setSearch(e.target.value)} />
      <div className="space-y-2">
        {filtered.map(m => (
          <div key={m.id} className="bg-panel border border-border rounded p-3">
            <div className="flex justify-between text-sm mb-1">
              <span className="text-accent">{m.address}</span>
              <span className={`text-xs ${m.direction === 'incoming' ? 'text-ok' : 'text-gray-400'}`}>
                {m.direction}
              </span>
              <span className="text-gray-500">{new Date(m.timestamp).toLocaleString()}</span>
            </div>
            <p className="text-white text-sm">{m.body}</p>
          </div>
        ))}
      </div>
    </div>
  )
}
