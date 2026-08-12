import { FormEvent, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { api, ApiError } from '../api'

type LogEntry = {
  id: number
  changedAt: string
  username: string
  userDisplayName: string
  eventType: string
  eventLabel: string
  target: string
  details: string
  venueLabel: string
  itemName: string
  oldValue: string
  newValue: string
}

const searchInputStyle: CSSProperties = {
  width: '100%',
  padding: '0.65rem 0.85rem',
  borderRadius: 10,
  border: '1px solid rgba(255,255,255,0.12)',
  background: 'rgba(255,255,255,0.06)',
  color: '#fff',
  caretColor: '#fff',
}

const EVENT_TYPE_OPTIONS = [
  { value: '', label: 'All events' },
  { value: 'USER_LOGIN', label: 'User logged in' },
  { value: 'USER_LOGOUT', label: 'User logged out' },
  { value: 'USER_CREATED', label: 'User created' },
  { value: 'USER_DELETED', label: 'User deleted' },
  { value: 'USER_ENABLED', label: 'User enabled' },
  { value: 'USER_DISABLED', label: 'User disabled' },
  { value: 'VENUE_CREATED', label: 'Venue created' },
  { value: 'WORK_ITEM_CREATED', label: 'Work item created' },
  { value: 'VENUE_STATUS_CHANGE', label: 'Venue status changed' },
] as const

function buildQuery(user: string, eventType: string, from: string, to: string) {
  const params = new URLSearchParams()
  if (user.trim()) params.set('user', user.trim())
  if (eventType) params.set('eventType', eventType)
  if (from) params.set('from', from)
  if (to) params.set('to', to)
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

export function LogsPage() {
  const [entries, setEntries] = useState<LogEntry[] | null>(null)
  const [error, setError] = useState('')
  const [userFilter, setUserFilter] = useState('')
  const [eventTypeFilter, setEventTypeFilter] = useState('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [applied, setApplied] = useState({ user: '', eventType: '', from: '', to: '' })

  const query = useMemo(
    () => buildQuery(applied.user, applied.eventType, applied.from, applied.to),
    [applied],
  )

  useEffect(() => {
    setEntries(null)
    void api
      .get<{ entries: LogEntry[] }>(`/api/logs${query}`)
      .then((data) => setEntries(data.entries || []))
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load logs'))
  }, [query])

  function applyFilters(e?: FormEvent) {
    e?.preventDefault()
    setError('')
    setApplied({ user: userFilter, eventType: eventTypeFilter, from: fromDate, to: toDate })
  }

  function clearFilters() {
    setUserFilter('')
    setEventTypeFilter('')
    setFromDate('')
    setToDate('')
    setApplied({ user: '', eventType: '', from: '', to: '' })
    setError('')
  }

  if (error) return <p className="error">{error}</p>
  if (!entries) return <p className="muted">Loading logs…</p>

  return (
    <div className="stack">
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: '1rem', flexWrap: 'wrap', alignItems: 'center' }}>
        <div>
          <h1 style={{ margin: 0 }}>Logs</h1>
          <p className="muted" style={{ margin: '0.35rem 0 0' }}>
            User activity, venue changes, and system events.
          </p>
        </div>
        <a className="btn" href={`/api/logs/export${query}`} target="_blank" rel="noreferrer">
          Export to PDF
        </a>
      </div>

      <form className="card" onSubmit={applyFilters} style={{ display: 'grid', gap: '0.75rem' }}>
        <div style={{ display: 'grid', gap: '0.75rem', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))' }}>
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="log-user-filter">User</label>
            <input
              id="log-user-filter"
              type="search"
              placeholder="Search by username or name…"
              value={userFilter}
              onChange={(e) => setUserFilter(e.target.value)}
              style={searchInputStyle}
            />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="log-event-type">Event type</label>
            <select
              id="log-event-type"
              value={eventTypeFilter}
              onChange={(e) => setEventTypeFilter(e.target.value)}
            >
              {EVENT_TYPE_OPTIONS.map((opt) => (
                <option key={opt.value || 'all'} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="log-from-date">From date</label>
            <input
              id="log-from-date"
              type="date"
              value={fromDate}
              onChange={(e) => setFromDate(e.target.value)}
            />
          </div>
          <div className="field" style={{ margin: 0 }}>
            <label htmlFor="log-to-date">To date</label>
            <input
              id="log-to-date"
              type="date"
              value={toDate}
              onChange={(e) => setToDate(e.target.value)}
            />
          </div>
        </div>
        <div className="row-actions">
          <button className="btn" type="submit">Apply filters</button>
          <button className="btn btn-secondary" type="button" onClick={clearFilters}>
            Clear
          </button>
        </div>
      </form>

      <div className="card table-wrap" style={{ padding: 0 }}>
        <table className="data">
          <thead>
            <tr>
              <th>When</th>
              <th>User</th>
              <th>Event</th>
              <th>Target</th>
              <th>Details</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 ? (
              <tr>
                <td colSpan={5} className="muted">
                  No log entries found for the selected filters.
                </td>
              </tr>
            ) : (
              entries.map((e) => (
                <tr key={e.id}>
                  <td>{formatWhen(e.changedAt)}</td>
                  <td>
                    <strong>{e.userDisplayName}</strong>
                    <div className="muted" style={{ fontSize: '0.8rem' }}>{e.username}</div>
                  </td>
                  <td>{e.eventLabel}</td>
                  <td>{e.target}</td>
                  <td>{formatDetails(e)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function formatDetails(e: LogEntry) {
  if (e.details) return e.details
  if (e.oldValue && e.newValue) return `${e.oldValue} → ${e.newValue}`
  if (e.venueLabel && e.itemName) return `${e.venueLabel} / ${e.itemName}`
  return '—'
}

function formatWhen(iso: string) {
  if (!iso) return ''
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}
