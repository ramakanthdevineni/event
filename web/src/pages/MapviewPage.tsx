import { useEffect, useRef, useState } from 'react'
import { api, ApiError } from '../api'
import type { MapData } from '../types'

export function MapviewPage() {
  const [data, setData] = useState<MapData | null>(null)
  const [error, setError] = useState('')
  const [scale, setScale] = useState(1)
  const [tx, setTx] = useState(0)
  const [ty, setTy] = useState(0)
  const dragging = useRef(false)
  const last = useRef({ x: 0, y: 0 })
  const svgRef = useRef<SVGSVGElement>(null)

  useEffect(() => {
    void api
      .get<MapData>('/api/mapview')
      .then(setData)
      .catch((err) => setError(err instanceof ApiError ? err.message : 'Failed to load map'))
  }, [])

  function reset() {
    setScale(1)
    setTx(0)
    setTy(0)
  }

  if (error) return <p className="error">{error}</p>
  if (!data) return <p className="muted">Loading map…</p>

  return (
    <div style={{ display: 'grid', gap: '1rem', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))' }}>
      <div className="card">
        <h1 style={{ marginTop: 0 }}>Mapview</h1>
        <p className="muted">Saudi Arabia venues colored by completion progress.</p>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '0.6rem', marginBottom: '1rem', fontSize: '0.85rem' }}>
          <span><i style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: '#dc2626', marginRight: 6 }} />0-24%</span>
          <span><i style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: '#ea580c', marginRight: 6 }} />25-49%</span>
          <span><i style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: '#ca8a04', marginRight: 6 }} />50-74%</span>
          <span><i style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: '#65a30d', marginRight: 6 }} />75-99%</span>
          <span><i style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: '#16a34a', marginRight: 6 }} />100%</span>
        </div>
        <div className="map-viewport">
          <div className="map-zoom">
            <button type="button" onClick={() => setScale((s) => Math.min(4, s * 1.2))}>+</button>
            <button type="button" onClick={() => setScale((s) => Math.max(0.6, s / 1.2))}>−</button>
            <button type="button" onClick={reset}>↻</button>
          </div>
          <svg
            ref={svgRef}
            className={`map-svg${dragging.current ? ' dragging' : ''}`}
            viewBox={data.viewBox}
            onWheel={(e) => {
              e.preventDefault()
              setScale((s) => Math.min(4, Math.max(0.6, s * (e.deltaY < 0 ? 1.1 : 0.9))))
            }}
            onPointerDown={(e) => {
              dragging.current = true
              last.current = { x: e.clientX, y: e.clientY }
              ;(e.target as Element).setPointerCapture?.(e.pointerId)
            }}
            onPointerMove={(e) => {
              if (!dragging.current) return
              const dx = e.clientX - last.current.x
              const dy = e.clientY - last.current.y
              last.current = { x: e.clientX, y: e.clientY }
              setTx((v) => v + dx)
              setTy((v) => v + dy)
            }}
            onPointerUp={() => { dragging.current = false }}
            onPointerCancel={() => { dragging.current = false }}
          >
            <g transform={`translate(${tx} ${ty}) scale(${scale})`} style={{ transformOrigin: 'center' }}>
              <path d={data.landPath} fill="#1d4ed8" stroke="#93c5fd" strokeWidth={2} opacity={0.85} />
              {data.markers.map((m) => (
                <g key={m.id}>
                  <circle cx={m.x} cy={m.y} r={10} fill={m.color} stroke="#fff" strokeWidth={1.5} />
                  <text x={m.x + 14} y={m.y + 4} fill="#f8fafc" fontSize={13} stroke="#0f172a" strokeWidth={3} paintOrder="stroke">
                    {m.label}
                  </text>
                </g>
              ))}
            </g>
          </svg>
        </div>
        <p className="muted" style={{ fontSize: '0.82rem' }}>Scroll to zoom, drag to pan.</p>
      </div>
      <div className="card">
        <h2 style={{ marginTop: 0 }}>Venues</h2>
        <div className="table-wrap">
          <table className="data">
            <thead><tr><th>Venue</th><th>Progress</th></tr></thead>
            <tbody>
              {data.venues.map((v) => (
                <tr key={v.id}>
                  <td><span style={{ display: 'inline-block', width: 12, height: 12, borderRadius: '50%', background: v.color, marginRight: 8 }} />{v.label}</td>
                  <td>{v.percent}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}
