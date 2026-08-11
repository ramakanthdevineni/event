import { useEffect, useState } from 'react'
import { useParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import type { StatusDef, WorkItem } from '../types'

type VenuePayload = {
  id: number
  label: string
  workItems: WorkItem[]
  statuses: StatusDef[]
}

export function VenuePage() {
  const { id } = useParams()
  const [data, setData] = useState<VenuePayload | null>(null)
  const [error, setError] = useState('')

  async function load() {
    if (!id) return
    setData(await api.get<VenuePayload>(`/api/venues/${id}`))
  }

  useEffect(() => {
    void load().catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load venue'))
  }, [id])

  async function updateStatus(itemName: string, status: string) {
    if (!id) return
    await api.post(`/api/venues/${id}`, { itemName, status })
    await load()
  }

  if (error) return <p className="error">{error}</p>
  if (!data) return <p className="muted">Loading venue…</p>

  return (
    <div className="card">
      <h1 style={{ margin: '0 0 0.25rem' }}>Welcome to {data.label}</h1>
      <p className="muted" style={{ margin: 0 }}>Track progress for the work items below.</p>
      <div className="table-wrap" style={{ marginTop: '1.25rem' }}>
        <table className="data">
          <thead><tr><th>Work Item</th><th>Status</th></tr></thead>
          <tbody>
            {data.workItems.map((item) => (
              <tr key={item.name}>
                <td><strong>{item.name}</strong></td>
                <td>
                  <select
                    value={item.status}
                    onChange={(e) => void updateStatus(item.name, e.target.value)}
                    style={{ minWidth: 160, padding: '0.5rem 0.75rem', borderRadius: 10, border: '1px solid rgba(255,255,255,0.25)', background: '#1e293b', color: '#f8fafc' }}
                  >
                    {data.statuses.map((s) => (
                      <option key={s.id} value={s.label}>{s.label}</option>
                    ))}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
