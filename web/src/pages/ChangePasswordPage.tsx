import { FormEvent, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'

export function ChangePasswordPage() {
  const { me, refresh } = useAuth()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError('')
    if (password !== confirmPassword) {
      setError('Passwords do not match.')
      return
    }
    try {
      const data = await api.post<{ homePath: string }>('/api/change-password', { password, confirmPassword })
      await refresh()
      navigate(data.homePath || '/dashboard', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to update password')
    }
  }

  return (
    <div className="login-page">
      <main className="login-card">
        <h1>Change Password</h1>
        <p>Hello {me?.firstName || ''}, set a new password to continue.</p>
        <form onSubmit={onSubmit}>
          <div className="field">
            <label>New Password</label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </div>
          <div className="field">
            <label>Confirm Password</label>
            <input type="password" value={confirmPassword} onChange={(e) => setConfirmPassword(e.target.value)} required />
          </div>
          {error && <p className="error">{error}</p>}
          <button className="btn" type="submit" style={{ width: '100%' }}>Update Password</button>
        </form>
      </main>
    </div>
  )
}
