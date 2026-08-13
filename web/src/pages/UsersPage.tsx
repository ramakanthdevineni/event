import { FormEvent, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api'
import { useAuth } from '../auth'
import { RoleDropdown } from '../components/RoleDropdown'
import type { UserRow, Venue } from '../types'

type UsersPayload = { users: UserRow[]; venues: Venue[]; canManage: boolean; page?: number; pageSize?: number; total?: number }

export function UsersPage() {
  const { me } = useAuth()
  const [data, setData] = useState<UsersPayload | null>(null)
  const [q, setQ] = useState('')
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [editUser, setEditUser] = useState<UserRow | null>(null)
  const [roleDrafts, setRoleDrafts] = useState<Record<string, string[]>>({})
  const [editForm, setEditForm] = useState({ firstName: '', lastName: '', email: '', roles: [] as string[] })
  const [importing, setImporting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [bulkRoles, setBulkRoles] = useState<string[]>([])
  const [bulkBusy, setBulkBusy] = useState(false)
  const [page, setPage] = useState(1)
  const pageSize = 100

  const roleOptions = useMemo(() => {
    const venues = data?.venues || []
    return [
      { value: 'user', label: 'User' },
      { value: 'admin', label: 'Admin' },
      ...venues.map((v) => ({ value: v.label, label: v.label })),
    ]
  }, [data])

  async function load(nextPage = page) {
    const payload = await api.get<UsersPayload>(`/api/users?page=${nextPage}&pageSize=${pageSize}`)
    setData(payload)
    setPage(payload.page ?? nextPage)
    const drafts: Record<string, string[]> = {}
    payload.users.forEach((u) => {
      drafts[u.username] = [...u.roles]
    })
    setRoleDrafts(drafts)
    setSelected(new Set())
  }

  useEffect(() => {
    void load(page).catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load users'))
  }, [page])

  const filtered = (data?.users || []).filter((u) => {
    const s = q.trim().toLowerCase()
    if (!s) return true
    return `${u.firstName} ${u.lastName}`.toLowerCase().includes(s) || u.email.toLowerCase().includes(s)
  })

  const selectedCount = selected.size
  const allFilteredSelected = filtered.length > 0 && filtered.every((u) => selected.has(u.username))

  function toggleSelected(username: string) {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(username)) next.delete(username)
      else next.add(username)
      return next
    })
  }

  function toggleSelectAllFiltered() {
    if (allFilteredSelected) {
      setSelected((prev) => {
        const next = new Set(prev)
        filtered.forEach((u) => next.delete(u.username))
        return next
      })
    } else {
      setSelected((prev) => {
        const next = new Set(prev)
        filtered.forEach((u) => next.add(u.username))
        return next
      })
    }
  }

  async function applyBulkRoles() {
    if (selectedCount === 0) return
    if (!confirm(`Update roles for ${selectedCount} selected user(s)?`)) return
    setError('')
    setSuccess('')
    setBulkBusy(true)
    try {
      const res = await api.put<{ message: string; errors?: string[] }>('/api/users/bulk-roles', {
        usernames: Array.from(selected),
        roles: bulkRoles,
      })
      await load()
      const detail = res.errors?.length ? `\n\n${res.errors.join('\n')}` : ''
      const msg = `${res.message}${detail}`
      setSuccess(res.message)
      setBulkRoles([])
      window.alert(msg)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to update roles')
    } finally {
      setBulkBusy(false)
    }
  }

  async function saveRoles(username: string) {
    setError('')
    setSuccess('')
    try {
      const roles = roleDrafts[username] || []
      await api.put('/api/users', { action: 'update-role', username, roles })
      await load()
      const label = roles.length ? roles.join(', ') : '(none)'
      const msg = `Roles updated for ${username}: ${label}`
      setSuccess(msg)
      window.alert(msg)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to save roles')
    }
  }

  async function toggleEnabled(username: string) {
    setError('')
    setSuccess('')
    try {
      await api.put('/api/users', { action: 'toggle-enabled', username })
      await load()
      setSuccess(`User status updated for ${username}.`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to update user status')
    }
  }

  async function removeUser(username: string) {
    if (!confirm(`Delete user ${username}?`)) return
    setError('')
    setSuccess('')
    await api.put('/api/users', { action: 'delete', username })
    await load()
    setSuccess(`User ${username} deleted.`)
  }

  async function saveEdit(e: FormEvent) {
    e.preventDefault()
    if (!editUser) return
    setError('')
    setSuccess('')
    try {
      await api.put('/api/users', {
        action: 'update',
        username: editUser.username,
        firstName: editForm.firstName,
        lastName: editForm.lastName,
        email: editForm.email,
        roles: editForm.roles,
      })
      const name = editUser.username
      setEditUser(null)
      await load()
      const msg = `User ${name} updated successfully.`
      setSuccess(msg)
      window.alert(msg)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to update user')
    }
  }

  async function importCsv(file: File) {
    setError('')
    setSuccess('')
    setImporting(true)
    try {
      const form = new FormData()
      form.append('file', file)
      const res = await fetch('/api/users/import', { method: 'POST', credentials: 'include', body: form })
      const data = await res.json().catch(() => ({})) as {
        message?: string
        errors?: string[]
      }
      if (!res.ok) throw new ApiError(res.status, data.message || 'Import failed')
      await load()
      const detail = data.errors?.length ? `\n\n${data.errors.join('\n')}` : ''
      const msg = `${data.message || 'Import finished.'}${detail}`
      setSuccess(data.message || 'Import finished.')
      window.alert(msg)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to import users')
    } finally {
      setImporting(false)
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
          <div className="row-actions" style={{ flexWrap: 'wrap' }}>
            <a className="btn btn-secondary" href="/api/users/export">Export CSV</a>
            <label className="btn btn-secondary" style={{ cursor: importing ? 'wait' : 'pointer' }}>
              {importing ? 'Importing…' : 'Import CSV'}
              <input
                type="file"
                accept=".csv,text/csv"
                hidden
                disabled={importing}
                onChange={(e) => {
                  const file = e.target.files?.[0]
                  e.target.value = ''
                  if (file) void importCsv(file)
                }}
              />
            </label>
            <Link className="btn" to="/users/add">+ Add User</Link>
          </div>
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
      {success && <p className="success">{success}</p>}

      {data.canManage && selectedCount > 0 && (
        <div className="card" style={{ display: 'flex', gap: '0.75rem', flexWrap: 'wrap', alignItems: 'center' }}>
          <span className="muted">{selectedCount} user{selectedCount === 1 ? '' : 's'} selected</span>
          <div style={{ flex: 1, minWidth: 200, maxWidth: 420 }}>
            <RoleDropdown
              options={roleOptions}
              selected={bulkRoles}
              onChange={setBulkRoles}
              summaryFallback="Choose roles to apply…"
            />
          </div>
          <button type="button" className="btn" disabled={bulkBusy || bulkRoles.length === 0} onClick={() => void applyBulkRoles()}>
            {bulkBusy ? 'Updating…' : 'Apply roles to selected'}
          </button>
          <button type="button" className="btn btn-secondary" onClick={() => setSelected(new Set())}>
            Clear selection
          </button>
        </div>
      )}

      <div className="card table-wrap" style={{ padding: 0 }}>
        <table className="data">
          <thead>
            <tr>
              {data.canManage && (
                <th style={{ width: 40 }}>
                  <input
                    type="checkbox"
                    aria-label="Select all users"
                    checked={allFilteredSelected}
                    onChange={toggleSelectAllFiltered}
                  />
                </th>
              )}
              <th>Name</th><th>Email</th><th>Status</th><th>Role</th><th>Actions</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((u) => (
              <tr key={u.username} style={{ opacity: u.enabled ? 1 : 0.65 }}>
                {data.canManage && (
                  <td>
                    <input
                      type="checkbox"
                      aria-label={`Select ${u.username}`}
                      checked={selected.has(u.username)}
                      onChange={() => toggleSelected(u.username)}
                    />
                  </td>
                )}
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

      {data.total != null && data.total > pageSize && (
        <div className="card" style={{ display: 'flex', gap: '0.75rem', alignItems: 'center', justifyContent: 'center' }}>
          <button type="button" className="btn btn-secondary btn-sm" disabled={page <= 1} onClick={() => setPage((p) => Math.max(1, p - 1))}>
            Previous
          </button>
          <span className="muted">
            Page {page} of {Math.max(1, Math.ceil(data.total / pageSize))} ({data.total} users)
          </span>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            disabled={page * pageSize >= data.total}
            onClick={() => setPage((p) => p + 1)}
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
