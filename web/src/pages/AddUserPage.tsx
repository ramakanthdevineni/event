import { FormEvent, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'

export function AddUserPage() {
  const [firstName, setFirstName] = useState('')
  const [lastName, setLastName] = useState('')
  const [email, setEmail] = useState('')
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setMessage('')
    setError('')
    try {
      const res = await api.post<{ message: string }>('/api/users', { firstName, lastName, email })
      setMessage(res.message)
      setFirstName('')
      setLastName('')
      setEmail('')
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to create user')
    }
  }

  return (
    <div className="card" style={{ maxWidth: 560 }}>
      <h1 style={{ marginTop: 0 }}>Add User</h1>
      <p className="muted">Email becomes the username. A default password is assigned.</p>
      <form onSubmit={onSubmit}>
        <div className="field"><label>First Name</label><input value={firstName} onChange={(e) => setFirstName(e.target.value)} required /></div>
        <div className="field"><label>Last Name</label><input value={lastName} onChange={(e) => setLastName(e.target.value)} required /></div>
        <div className="field"><label>Email</label><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required /></div>
        {message && <p className="success">{message}</p>}
        {error && <p className="error">{error}</p>}
        <div className="row-actions">
          <button className="btn" type="submit">Create User</button>
          <Link className="btn btn-secondary" to="/users">Back</Link>
        </div>
      </form>
    </div>
  )
}
