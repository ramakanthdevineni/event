import { FormEvent, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import type { Me } from '../types'

export function LoginPage() {
  const { me, loading, refresh } = useAuth()
  const navigate = useNavigate()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  if (!loading && me) {
    return <Navigate to={me.mustChangePassword ? '/change-password' : me.homePath} replace />
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const data = await api.post<Me>('/api/login', { username, password })
      await refresh()
      navigate(data.mustChangePassword ? '/change-password' : data.homePath, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Login failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-page">
      <main className="login-card">
        <div className="login-card-header">
          <h1>VMS</h1>
          <p>Hello Venue Technology Team</p>
        </div>
        <form onSubmit={onSubmit}>
          <div className="field">
            <label htmlFor="username">Username</label>
            <input id="username" value={username} onChange={(e) => setUsername(e.target.value)} placeholder="Enter username" autoComplete="username" required />
          </div>
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Enter password" autoComplete="current-password" required />
          </div>
          {error && <p className="error">{error}</p>}
          <button className="btn" type="submit" disabled={busy} style={{ width: '100%', marginTop: '0.35rem' }}>
            {busy ? 'Signing in…' : 'Sign In'}
          </button>
        </form>
      </main>
    </div>
  )
}
