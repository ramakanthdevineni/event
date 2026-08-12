import { useEffect, useLayoutEffect, useRef, useState } from 'react'

type Props = {
  options: { value: string; label: string }[]
  selected: string[]
  onChange: (next: string[]) => void
  summaryFallback?: string
}

export function RoleDropdown({ options, selected, onChange, summaryFallback = 'Select roles...' }: Props) {
  const rootRef = useRef<HTMLDivElement>(null)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null)
  const ignoreUntil = useRef(0)

  const summary =
    selected.length === 0
      ? summaryFallback
      : selected
          .map((v) => options.find((o) => o.value === v)?.label || v)
          .join(', ')

  const place = () => {
    const toggle = toggleRef.current
    if (!toggle) return
    const rect = toggle.getBoundingClientRect()
    const width = Math.max(rect.width, 240)
    let left = rect.left
    if (left + width > window.innerWidth - 8) left = Math.max(8, window.innerWidth - width - 8)
    let top = rect.bottom + 4
    const maxH = 280
    if (top + Math.min(maxH, 220) > window.innerHeight && rect.top > maxH) {
      top = Math.max(8, rect.top - maxH - 4)
    }
    setPos({ top, left, width })
  }

  useLayoutEffect(() => {
    if (open) place()
  }, [open])

  useEffect(() => {
    if (!open) return
    const onOutside = (e: Event) => {
      if (Date.now() < ignoreUntil.current) return
      const t = e.target as Node
      if (rootRef.current?.contains(t) || panelRef.current?.contains(t)) return
      setOpen(false)
    }
    let lastW = window.innerWidth
    const onResize = () => {
      if (window.innerWidth !== lastW) {
        lastW = window.innerWidth
        setOpen(false)
      }
    }
    document.addEventListener('click', onOutside)
    document.addEventListener('touchend', onOutside, { passive: true })
    window.addEventListener('resize', onResize)
    return () => {
      document.removeEventListener('click', onOutside)
      document.removeEventListener('touchend', onOutside)
      window.removeEventListener('resize', onResize)
    }
  }, [open])

  const filtered = options.filter((o) => o.label.toLowerCase().includes(query.trim().toLowerCase()))
  const filteredLabel = query.trim() ? ` (${filtered.length} shown)` : ''

  const selectAll = () => {
    const values = filtered.map((o) => o.value)
    const merged = [...selected]
    for (const v of values) {
      if (!merged.some((s) => s.toLowerCase() === v.toLowerCase())) merged.push(v)
    }
    onChange(merged)
  }

  const unselectAll = () => {
    if (!query.trim()) {
      onChange([])
      return
    }
    const remove = new Set(filtered.map((o) => o.value.toLowerCase()))
    onChange(selected.filter((s) => !remove.has(s.toLowerCase())))
  }

  return (
    <div className="role-dd" ref={rootRef} data-role-dropdown>
      <button
        type="button"
        className="role-dd-toggle"
        ref={toggleRef}
        aria-expanded={open}
        onClick={(e) => {
          e.preventDefault()
          e.stopPropagation()
          if (open) {
            setOpen(false)
          } else {
            ignoreUntil.current = Date.now() + 500
            setOpen(true)
          }
        }}
      >
        <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{summary}</span>
        <span>▾</span>
      </button>
      {open && pos && (
        <div
          className="role-dd-panel"
          ref={panelRef}
          style={{ top: pos.top, left: pos.left, width: pos.width }}
          onClick={(e) => e.stopPropagation()}
          onTouchStart={(e) => e.stopPropagation()}
        >
          <input
            type="search"
            placeholder="Search roles..."
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoComplete="off"
          />
          <div className="role-dd-actions">
            <button type="button" onClick={selectAll}>
              Select all{filteredLabel}
            </button>
            <button type="button" onClick={unselectAll}>
              Unselect all{filteredLabel}
            </button>
          </div>
          <div className="role-checks">
            {filtered.map((o) => {
              const checked = selected.some((s) => s.toLowerCase() === o.value.toLowerCase())
              return (
                <label key={o.value} className="role-check">
                  <input
                    type="checkbox"
                    checked={checked}
                    onChange={() => {
                      const next = checked
                        ? selected.filter((s) => s.toLowerCase() !== o.value.toLowerCase())
                        : [...selected, o.value]
                      onChange(next)
                    }}
                    onClick={(e) => e.stopPropagation()}
                  />
                  <span>{o.label}</span>
                </label>
              )
            })}
          </div>
        </div>
      )}
    </div>
  )
}
