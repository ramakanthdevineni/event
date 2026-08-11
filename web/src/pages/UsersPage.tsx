import { FormEvent, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import { RoleDropdown } from '../components/RoleDropdown'
import type { UserRow, Venue } from '../types'

type UsersPayload = { users: UserRow[]; venues: Venue[]; canManage: boolean }

export function UsersPage() {
  const { me } = useAuth()
  const [data, setData] = useState<UsersPayload | null>(null)
  const [q, setQ] = useState('')
  const [error, setError] = useState('')
  const [editUser, setEditUser] = useState<UserRow | null>(null)
  const [roleDrafts, setRoleDrafts] = useState<Record<string, string[]>>({})
  const [editForm, setEditForm] = useState({ firstName: '', lastName: '', email: '', roles: [] as string[] })

  const roleOptions = useMemo(() => {
    const venues = data?.venues || []
    return [
      { value: 'user', label: 'User' },
      { value: 'admin', label: 'Admin' },
      ...venues.map((v) => ({ value: v.label, label: v.label })),
    ]
  }, [data])

  async function load() {
    const payload = await api.get<UsersPayload>('/api/users')
    setData(payload)
    const drafts: Record<string, string[]> = {}
    payload.users.forEach((u) => {
      drafts[u.username] = [...u.roles]
    })
    setRoleDrafts(drafts)
  }

  useEffect(() => {
    void load().catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load users'))
  }, [])

  const filtered = (data?.users || []).filter((u) => {
    const s = q.trim().toLowerCase()
    if (!s) return true
    return `${u.firstName} ${u.lastName}`.toLowerCase().includes(s) || u.email.toLowerCase().includes(s)
  })

  async function saveRoles(username: string) {
    setError('')
    try {
      await api.put('/api/users', { action: 'update-role', username, roles: roleDrafts[username] || [] })
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to save roles')
    }
  }

  async function toggleEnabled(username: string) {
    await api.put('/api/users', { action: 'toggle-enabled', username })
    await load()
  }

  async function removeUser(username: string) {
    if (!confirm(`Delete user ${username}?`)) return
    await api.put('/api/users', { action: 'delete', username })
    await load()
  }

  async function saveEdit(e: FormEvent) {
    e.preventDefault()
    if (!editUser) return
    setError('')
    try {
      await api.put('/api/users', {
        action: 'update',
        username: editUser.username,
        firstName: editForm.firstName,
        lastName: editForm.lastName,
        email: editForm.email,
        roles: editForm.roles,
      })
      setEditUser(null)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to update user')
    }
  }

  if (!data) return <p className="muted">Loading users…</p>

  return (
    <div className="stack">
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <div>
          <h2 style={{ margin: 0 }}>Welcome, {me?.firstName}</h2>
          <p className="muted" style={{ margin: '0.25rem 0 0' }}>Signed in as {me?.username}</p>
        </div>
        {data.canManage && (
          <Link className="btn" to="/users/add">+ Add User</Link>
        )}
      </div>

      {editUser && data.canManage && (
        <div className="card">
          <h3 style={{ marginTop: 0 }}>Edit {editUser.username}</h3>
          <form onSubmit={saveEdit}>
            <div className="field"><label>First Name</label><input value={editForm.firstName} onChange={(e) => setEditForm({ ...editForm, firstName: e.target.value })} required /></div>
            <div className="field"><label>Last Name</label><input value={editForm.lastName} onChange={(e) => setEditForm({ ...editForm, lastName: e.target.value })} required /></div>
            <div className="field"><label>Email</label><input type="email" value={editForm.email} onChange={(e) => setEditForm({ ...editForm, email: e.target.value })} required /></div>
            <div className="field">
              <label>Roles</label>
              <RoleDropdown options={roleOptions} selected={editForm.roles} onChange={(roles) => setEditForm({ ...editForm, roles })} />
            </div>
            <div className="row-actions">
              <button className="btn" type="submit">Save Changes</button>
              <button className="btn btn-secondary" type="button" onClick={() => setEditUser(null)}>Cancel</button>
            </div>
          </form>
        </div>
      )}

      <input
        type="search"
        placeholder="Search by name or email..."
        value={q}
        onChange={(e) => setQ(e.target.value)}
        style={{ width: '100%', padding: '0.65rem 0.85rem', borderRadius: 10, border: '1px solid rgba(255,255,255,0.12)', background: 'rgba(255,255,255,0.06)', color: '#fff', caretColor: '#fff' }}
      />

      {error && <p className="error">{error}</p>}

      <div className="card table-wrap" style={{ padding: 0 }}>
        <table className="data">
          <thead>
            <tr><th>Name</th><th>Email</th><th>Status</th><th>Role</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {filtered.map((u) => (
              <tr key={u.username} style={{ opacity: u.enabled ? 1 : 0.65 }}>
                <td>{u.firstName} {u.lastName}</td>
                <td>{u.email}</td>
                <td><span className={`badge ${u.enabled ? 'badge-ok' : 'badge-off'}`}>{u.enabled ? 'Active' : 'Disabled'}</span></td>
                <td>
                  {data.canManage ? (
                    <div className="row-actions">
                      <div style={{ flex: 1, minWidth: 140 }}>
                        <RoleDropdown
                          options={roleOptions}
                          selected={roleDrafts[u.username] || []}
                          onChange={(roles) => setRoleDrafts((d) => ({ ...d, [u.username]: roles }))}
                        />
                      </div>
                      <button type="button" className="btn btn-sm" onClick={() => void saveRoles(u.username)}>Save</button>
                    </div>
                  ) : (
                    u.roles.join(', ')
                  )}
                </td>
                <td>
                  <div className="row-actions">
                    {data.canManage && (
                      <>
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => {
                            setEditUser(u)
                            setEditForm({ firstName: u.firstName, lastName: u.lastName, email: u.email, roles: [...u.roles] })
                          }}
                        >
                          Edit
                        </button>
                        {u.username !== me?.username && (
                          <>
                            <button type="button" className="btn btn-sm btn-secondary" onClick={() => void toggleEnabled(u.username)}>
                              {u.enabled ? 'Disable' : 'Enable'}
                            </button>
                            <button type="button" className="btn btn-sm btn-danger" onClick={() => void removeUser(u.username)}>Delete</button>
                          </>
                        )}
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
