import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { auth } from '../api/client'
import toast from 'react-hot-toast'

export default function Login() {
  const [user, setUser] = useState('')
  const [pass, setPass] = useState('')
  const [loading, setLoading] = useState(false)
  const nav = useNavigate()

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true)
    try {
      const r = await auth.login(user, pass)
      localStorage.setItem('token', r.data.token)
      nav('/')
    } catch {
      toast.error('Invalid credentials')
    } finally { setLoading(false) }
  }

  return (
    <div className="min-h-screen bg-surface flex items-center justify-center">
      <form onSubmit={submit} className="bg-panel border border-border rounded-lg p-8 w-96 space-y-4">
        <h1 className="text-white text-xl font-bold">Cyrax C2</h1>
        <input
          className="w-full bg-surface border border-border text-white px-3 py-2 rounded"
          placeholder="Username"
          value={user} onChange={e => setUser(e.target.value)}
        />
        <input
          className="w-full bg-surface border border-border text-white px-3 py-2 rounded"
          type="password" placeholder="Password"
          value={pass} onChange={e => setPass(e.target.value)}
        />
        <button
          type="submit" disabled={loading}
          className="w-full bg-accent hover:bg-purple-500 text-white py-2 rounded disabled:opacity-50"
        >
          {loading ? 'Signing in...' : 'Sign In'}
        </button>
      </form>
    </div>
  )
}
