import React, { useState } from 'react'
import toast from 'react-hot-toast'
import api from '../api/client'

export default function Settings() {
  const [webhookUrl, setWebhookUrl] = useState('')
  const [tgToken, setTgToken] = useState('')
  const [tgChatId, setTgChatId] = useState('')

  const save = async () => {
    try {
      await api.post('/settings', { webhook_url: webhookUrl, tg_token: tgToken, tg_chat_id: tgChatId })
      toast.success('Saved')
    } catch { toast.error('Failed') }
  }

  const logout = () => { localStorage.removeItem('token'); window.location.href = '/login' }

  return (
    <div className="min-h-screen bg-surface p-6">
      <h1 className="text-2xl font-bold text-white mb-6">Settings</h1>
      <div className="bg-panel border border-border rounded-lg p-6 space-y-4 max-w-xl">
        <div>
          <label className="text-gray-400 text-sm block mb-1">Discord Webhook URL</label>
          <input className="w-full bg-surface border border-border text-white px-3 py-2 rounded text-sm"
            value={webhookUrl} onChange={e => setWebhookUrl(e.target.value)} />
        </div>
        <div>
          <label className="text-gray-400 text-sm block mb-1">Telegram Bot Token</label>
          <input className="w-full bg-surface border border-border text-white px-3 py-2 rounded text-sm"
            value={tgToken} onChange={e => setTgToken(e.target.value)} />
        </div>
        <div>
          <label className="text-gray-400 text-sm block mb-1">Telegram Chat ID</label>
          <input className="w-full bg-surface border border-border text-white px-3 py-2 rounded text-sm"
            value={tgChatId} onChange={e => setTgChatId(e.target.value)} />
        </div>
        <button onClick={save} className="bg-accent hover:bg-purple-500 text-white px-4 py-2 rounded">Save</button>
        <button onClick={logout} className="ml-3 text-danger hover:text-red-400 text-sm">Logout</button>
      </div>
    </div>
  )
}
