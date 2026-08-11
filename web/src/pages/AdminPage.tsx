import { FormEvent, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import type { StatusDef, Venue, WorkItemDef } from '../types'

type AdminPayload = {
  venues: Venue[]
  workItems: WorkItemDef[]
  statuses: StatusDef[]
}

export function AdminPage() {
  const [data, setData] = useState<AdminPayload | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [venueName, setVenueName] = useState('')
  const [workName, setWorkName] = useState('')
  const [statusLabel, setStatusLabel] = useState('')
  const [statusPercent, setStatusPercent] = useState(0)
  const [editVenue, setEditVenue] = useState<Venue | null>(null)
  const [editWork, setEditWork] = useState<WorkItemDef | null>(null)
  const [editStatus, setEditStatus] = useState<StatusDef | null>(null)

  async function load() {
    setData(await api.get<AdminPayload>('/api/admin'))
  }

  useEffect(() => {
    void load().catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load admin'))
  }, [])

  async function run(fn: () => Promise<unknown>, ok = 'Saved.') {
    setError('')
    setMessage('')
    try {
      await fn()
      setMessage(ok)
      await load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Request failed')
    }
  }

  if (!data) return <p className="muted">Loading admin panel…</p>

  return (
    <div className="stack">
      <h1 style={{ margin: 0 }}>Admin Panel</h1>
      {message && <p className="success">{message}</p>}
      {error && <p className="error">{error}</p>}

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Venue</h2>
        <p className="muted">Add, rename, or remove venues shown in navigation and roles.</p>
        <form
          className="row-actions"
          onSubmit={(e: FormEvent) => {
            e.preventDefault()
            void run(async () => {
              await api.post('/api/admin', { action: 'venue-add', label: venueName })
              setVenueName('')
            }, 'Venue added.')
          }}
        >
          <input style={{ flex: 1, minWidth: 160, padding: '0.65rem', borderRadius: 10, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} placeholder="Venue name" value={venueName} onChange={(e) => setVenueName(e.target.value)} required />
          <button className="btn" type="submit">Add Venue</button>
        </form>
        <ul style={{ listStyle: 'none', padding: 0, margin: '1rem 0 0' }}>
          {data.venues.map((v) => (
            <li key={v.id} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', padding: '0.55rem 0', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
              {editVenue?.id === v.id ? (
                <>
                  <input style={{ flex: 1, padding: '0.45rem', borderRadius: 8, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} value={editVenue.label} onChange={(e) => setEditVenue({ ...editVenue, label: e.target.value })} />
                  <button className="btn btn-sm" type="button" onClick={() => void run(async () => { await api.put('/api/admin', { action: 'venue-update', id: v.id, label: editVenue.label }); setEditVenue(null) })}>Save</button>
                  <button className="btn btn-sm btn-secondary" type="button" onClick={() => setEditVenue(null)}>Cancel</button>
                </>
              ) : (
                <>
                  <strong style={{ flex: 1 }}>{v.label}</strong>
                  <button className="btn btn-sm" type="button" onClick={() => setEditVenue(v)}>Edit</button>
                  <button className="btn btn-sm btn-danger" type="button" onClick={() => void run(async () => api.delete('/api/admin', { action: 'venue-delete', id: v.id }), 'Venue deleted.')}>Delete</button>
                </>
              )}
            </li>
          ))}
        </ul>
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Work Items</h2>
        <p className="muted">These items appear on every venue page.</p>
        <form className="row-actions" onSubmit={(e) => { e.preventDefault(); void run(async () => { await api.post('/api/admin', { action: 'work-item-add', name: workName }); setWorkName('') }, 'Work item added.') }}>
          <input style={{ flex: 1, minWidth: 160, padding: '0.65rem', borderRadius: 10, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} placeholder="New work item name" value={workName} onChange={(e) => setWorkName(e.target.value)} required />
          <button className="btn" type="submit">Add Work Item</button>
        </form>
        <ul style={{ listStyle: 'none', padding: 0, margin: '1rem 0 0' }}>
          {data.workItems.map((w) => (
            <li key={w.id} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', padding: '0.55rem 0', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
              {editWork?.id === w.id ? (
                <>
                  <input style={{ flex: 1, padding: '0.45rem', borderRadius: 8, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} value={editWork.name} onChange={(e) => setEditWork({ ...editWork, name: e.target.value })} />
                  <button className="btn btn-sm" type="button" onClick={() => void run(async () => { await api.put('/api/admin', { action: 'work-item-update', id: w.id, name: editWork.name }); setEditWork(null) })}>Save</button>
                  <button className="btn btn-sm btn-secondary" type="button" onClick={() => setEditWork(null)}>Cancel</button>
                </>
              ) : (
                <>
                  <strong style={{ flex: 1 }}>{w.name}</strong>
                  <button className="btn btn-sm" type="button" onClick={() => setEditWork(w)}>Edit</button>
                  <button className="btn btn-sm btn-danger" type="button" onClick={() => void run(async () => api.delete('/api/admin', { action: 'work-item-delete', id: w.id }), 'Work item deleted.')}>Delete</button>
                </>
              )}
            </li>
          ))}
        </ul>
      </div>

      <div className="card">
        <h2 style={{ marginTop: 0 }}>Status Options</h2>
        <p className="muted">Percent values drive overall progress.</p>
        <form className="row-actions" onSubmit={(e) => { e.preventDefault(); void run(async () => { await api.post('/api/admin', { action: 'status-add', label: statusLabel, percent: statusPercent }); setStatusLabel(''); setStatusPercent(0) }, 'Status added.') }}>
          <input style={{ flex: 1, minWidth: 120, padding: '0.65rem', borderRadius: 10, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} placeholder="Status label" value={statusLabel} onChange={(e) => setStatusLabel(e.target.value)} required />
          <input style={{ width: 100, padding: '0.65rem', borderRadius: 10, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} type="number" min={0} max={100} value={statusPercent} onChange={(e) => setStatusPercent(Number(e.target.value))} required />
          <button className="btn" type="submit">Add Status</button>
        </form>
        <ul style={{ listStyle: 'none', padding: 0, margin: '1rem 0 0' }}>
          {data.statuses.map((s) => (
            <li key={s.id} style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', padding: '0.55rem 0', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
              {editStatus?.id === s.id ? (
                <>
                  <input style={{ flex: 1, padding: '0.45rem', borderRadius: 8, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} value={editStatus.label} onChange={(e) => setEditStatus({ ...editStatus, label: e.target.value })} />
                  <input style={{ width: 80, padding: '0.45rem', borderRadius: 8, border: '1px solid rgba(255,255,255,0.2)', background: '#111827', color: '#fff' }} type="number" min={0} max={100} value={editStatus.percent} onChange={(e) => setEditStatus({ ...editStatus, percent: Number(e.target.value) })} />
                  <button className="btn btn-sm" type="button" onClick={() => void run(async () => { await api.put('/api/admin', { action: 'status-update', id: s.id, label: editStatus.label, percent: editStatus.percent }); setEditStatus(null) })}>Save</button>
                  <button className="btn btn-sm btn-secondary" type="button" onClick={() => setEditStatus(null)}>Cancel</button>
                </>
              ) : (
                <>
                  <strong style={{ flex: 1 }}>{s.label} ({s.percent}%)</strong>
                  <button className="btn btn-sm" type="button" onClick={() => setEditStatus(s)}>Edit</button>
                  <button className="btn btn-sm btn-danger" type="button" onClick={() => void run(async () => api.delete('/api/admin', { action: 'status-delete', id: s.id }), 'Status deleted.')}>Delete</button>
                </>
              )}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
