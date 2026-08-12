import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api'
import type { VenueProgress } from '../types'

type StatusPayload = {
  venues: VenueProgress[]
  selected: VenueProgress | null
}

async function downloadStatusPdf() {
  const tz = Intl.DateTimeFormat().resolvedOptions().timeZone
  const res = await fetch(`/api/status/export?tz=${encodeURIComponent(tz)}`, { credentials: 'include' })
  if (!res.ok) {
    let message = 'Unable to export status PDF.'
    try {
      const data = (await res.json()) as { message?: string }
      if (data.message) message = data.message
    } catch {
      /* ignore */
    }
    throw new ApiError(res.status, message)
  }
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = 'status-report.pdf'
  link.click()
  URL.revokeObjectURL(url)
}

export function StatusPage() {
  const [params, setParams] = useSearchParams()
  const [data, setData] = useState<StatusPayload | null>(null)
  const [error, setError] = useState('')
  const [exporting, setExporting] = useState(false)
  const optionId = params.get('optionId')

  useEffect(() => {
    const q = optionId ? `?optionId=${encodeURIComponent(optionId)}` : ''
    void api
      .get<StatusPayload>(`/api/status${q}`)
      .then(setData)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load status'))
  }, [optionId])

  if (error) return <p className="error">{error}</p>
  if (!data) return <p className="muted">Loading status…</p>

  return (
    <div className="stack">
      <div className="top-actions" style={{ marginBottom: 0 }}>
        <button
          type="button"
          className="btn"
          disabled={exporting}
          onClick={() => {
            setExporting(true)
            setError('')
            void downloadStatusPdf()
              .catch((err) => setError(err instanceof ApiError ? err.message : 'Unable to export status PDF.'))
              .finally(() => setExporting(false))
          }}
        >
          {exporting ? 'Exporting…' : 'Export to PDF'}
        </button>
      </div>
      <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))' }}>
        <div className="card">
          <h2 style={{ marginTop: 0 }}>Overall Progress</h2>
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>Venue</th><th>Progress</th></tr></thead>
              <tbody>
                {data.venues.map((v) => (
                  <tr key={v.id} style={{ background: data.selected?.id === v.id ? 'rgba(255,255,255,0.08)' : undefined }}>
                    <td>
                      <button type="button" style={{ background: 'none', border: 'none', color: '#f8fafc', padding: 0, fontWeight: 600 }} onClick={() => setParams({ optionId: String(v.id) })}>
                        {v.label}
                      </button>
                    </td>
                    <td>
                      <div className="progress"><span style={{ width: `${v.percent}%`, background: v.color }} /></div>
                      <span className="muted">{v.percent}% overall</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
        <div className="card">
          {data.selected ? (
            <>
              <h3 style={{ marginTop: 0 }}>{data.selected.label}</h3>
              <p className="muted">Overall progress: <strong>{data.selected.percent}%</strong></p>
              <div className="table-wrap">
                <table className="data">
                  <thead><tr><th>Work Item</th><th>Status</th><th>Progress</th></tr></thead>
                  <tbody>
                    {data.selected.workItems.map((item) => (
                      <tr key={item.name}>
                        <td>{item.name}</td>
                        <td>{item.status}</td>
                        <td>
                          <Link to={`/venues/${data.selected!.id}`}>Open venue</Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          ) : (
            <p className="muted">Select a venue to view work-item details.</p>
          )}
        </div>
      </div>
    </div>
  )
}
