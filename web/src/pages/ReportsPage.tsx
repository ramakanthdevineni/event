import { useEffect, useState } from 'react'
import { api, ApiError } from '../api'

type ReportEntry = {
  id: number
  changedAt: string
  username: string
  userDisplayName: string
  venueId: number
  venueLabel: string
  itemName: string
  oldStatus: string
  newStatus: string
}

export function ReportsPage() {
  const [entries, setEntries] = useState<ReportEntry[] | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    void api
      .get<{ entries: ReportEntry[] }>('/api/reports')
      .then((data) => setEntries(data.entries || []))
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load reports'))
  }, [])

  if (error) return <p className="error">{error}</p>
  if (!entries) return <p className="muted">Loading reports…</p>

  return (
    <div className="stack">
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <div>
          <h1 style={{ margin: 0 }}>Reports</h1>
          <p className="muted" style={{ margin: '0.35rem 0 0' }}>
            Who changed venue work-item status, and when.
          </p>
        </div>
        <a className="btn" href="/api/reports/export" target="_blank" rel="noreferrer">Export to PDF</a>
      </div>

      <div className="card table-wrap" style={{ padding: 0 }}>
        <table className="data">
          <thead>
            <tr>
              <th>When</th>
              <th>User</th>
              <th>Venue</th>
              <th>Work Item</th>
              <th>From</th>
              <th>To</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 ? (
              <tr>
                <td colSpan={6} className="muted">No status changes recorded yet. Update a venue work item to create a log entry.</td>
              </tr>
            ) : (
              entries.map((e) => (
                <tr key={e.id}>
                  <td>{formatWhen(e.changedAt)}</td>
                  <td>
                    <strong>{e.userDisplayName}</strong>
                    <div className="muted" style={{ fontSize: '0.8rem' }}>{e.username}</div>
                  </td>
                  <td>{e.venueLabel}</td>
                  <td>{e.itemName}</td>
                  <td>{e.oldStatus}</td>
                  <td>{e.newStatus}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function formatWhen(iso: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}
