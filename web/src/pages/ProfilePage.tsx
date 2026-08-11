import { FormEvent, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'

export function ProfilePage() {
  const { me, refresh } = useAuth()
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  useEffect(() => {
    if (me) {
      setFirstName(me.firstName)
      setLastName(me.lastName)
      setEmail(me.email)
    }
  }, [me])

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setMessage('')
    setError('')
    try {
      await api.put('/api/profile', { firstName, lastName, email, password: password || undefined })
      setPassword('')
      setMessage('Profile updated successfully.')
      await refresh()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to save profile')
    }
  }

  if (!me) return null
  return (
    <div className="card" style={{ maxWidth: 560 }}>
      <h1 style={{ marginTop: 0 }}>Edit Profile</h1>
      <p className="muted">Update your user details below.</p>
      <form onSubmit={onSubmit}>
        <div className="field">
          <label>First Name</label>
          <input value={firstName} onChange={(e) => setFirstName(e.target.value)} required />
        </div>
        <div className="field">
          <label>Last Name</label>
          <input value={lastName} onChange={(e) => setLastName(e.target.value)} required />
        </div>
        <div className="field">
          <label>Email</label>
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </div>
        <div className="field">
          <label>New Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Leave blank to keep current password" />
        </div>
        <div className="field">
          <label>Username</label>
          <input value={me.username} disabled />
        </div>
        {message && <p className="success">{message}</p>}
        {error && <p className="error">{error}</p>}
        <button className="btn" type="submit">Save Changes</button>
      </form>
    </div>
  )
}
