import React, { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { files as fileApi } from '../api/client'
import toast from 'react-hot-toast'

interface FileEntry { id: string; name: string; path: string; size: number; ext: string }

export default function FileBrowser() {
  const { id } = useParams<{ id: string }>()
  const [list, setList] = useState<FileEntry[]>([])
  const [filter, setFilter] = useState('')

  useEffect(() => {
    if (id) fileApi.list(id).then(r => setList(r.data.files ?? []))
  }, [id])

  const download = async (fileId: string, name: string) => {
    if (!id) return
    try {
      const r = await fileApi.download(id, fileId)
      const url = URL.createObjectURL(r.data)
      const a = document.createElement('a')
      a.href = url; a.download = name; a.click()
      URL.revokeObjectURL(url)
    } catch { toast.error('Download failed') }
  }

  const fmt = (n: number) => n > 1e6 ? `${(n/1e6).toFixed(1)}MB` : `${(n/1e3).toFixed(0)}KB`

  const filtered = list.filter(f =>
    f.name.toLowerCase().includes(filter.toLowerCase()) ||
    f.ext.includes(filter)
  )

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-4">Files — {list.length}</h1>
      <input className="bg-panel border border-border text-white px-3 py-2 rounded w-full mb-4"
        placeholder="Filter…" value={filter} onChange={e => setFilter(e.target.value)} />
      <div className="space-y-1">
        {filtered.map(f => (
          <div key={f.id} className="bg-panel border border-border rounded p-3 flex justify-between items-center">
            <div>
              <span className="text-white text-sm">{f.name}</span>
              <span className="text-gray-500 text-xs ml-2">{f.path}</span>
            </div>
            <div className="flex items-center gap-3">
              <span className="text-gray-400 text-xs">{fmt(f.size)}</span>
              <button onClick={() => download(f.id, f.name)}
                className="text-accent hover:text-purple-400 text-sm">↓</button>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
