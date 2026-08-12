import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'
import type { Me, NavItem } from './types'

function useVenueDropdown(me: Me): boolean {
  if (me.isAdmin) return true
  const hasUserRole = me.roles.some((r) => r.toLowerCase() === 'user')
  return me.readOnly || (hasUserRole && !me.isAdmin)
}

function NavVenueDropdown({ venues, onNavigate }: { venues: NavItem[]; onNavigate: () => void }) {
  const location = useLocation()
  const activeVenue = venues.find((v) => location.pathname === v.href)
  const [open, setOpen] = useState(() => Boolean(activeVenue))

  useEffect(() => {
    if (activeVenue) setOpen(true)
  }, [activeVenue?.href])

  return (
    <div className={`nav-venue-group${open ? ' open' : ''}`}>
      <button
        type="button"
        className={`nav-venue-toggle${activeVenue ? ' active' : ''}`}
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span>{activeVenue ? activeVenue.label : 'Venues'}</span>
        <span className="nav-venue-caret" aria-hidden>{open ? '▾' : '▸'}</span>
      </button>
      {open && (
        <div className="nav-venue-panel">
          {venues.map((item) => (
            <NavLink
              key={item.href}
              to={item.href}
              className={({ isActive }) => `nav-link nav-venue-link${isActive ? ' active' : ''}`}
              onClick={onNavigate}
            >
              {item.label}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  )
}

export function AppLayout() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const sideRef = useRef<HTMLElement>(null)

  const { mainNav, venueNav } = useMemo(() => {
    if (!me) return { mainNav: [] as NavItem[], venueNav: [] as NavItem[] }
    if (!useVenueDropdown(me)) return { mainNav: me.nav, venueNav: [] as NavItem[] }
    const main: NavItem[] = []
    const venues: NavItem[] = []
    for (const item of me.nav) {
      if (item.href.startsWith('/venues/')) venues.push(item)
      else main.push(item)
    }
    return { mainNav: main, venueNav: venues }
  }, [me])

  useEffect(() => {
    const onDoc = (e: MouseEvent | TouchEvent) => {
      if (!sideRef.current?.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('click', onDoc)
    document.addEventListener('touchend', onDoc)
    return () => {
      document.removeEventListener('click', onDoc)
      document.removeEventListener('touchend', onDoc)
    }
  }, [])

  if (!me) return null

  return (
    <div className="app-shell">
      <aside className={`sidebar${open ? ' open' : ''}`} ref={sideRef} id="app-sidebar">
        <button type="button" className="menu-toggle" onClick={(e) => { e.stopPropagation(); setOpen((v) => !v) }}>
          <span aria-hidden>☰</span> Menu
        </button>
        <div className="sidebar-panel">
          <h2>Navigation</h2>
          <nav>
            {mainNav.map((item) => (
              <NavLink
                key={item.href}
                to={item.href}
                className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                onClick={() => setOpen(false)}
              >
                {item.label}
              </NavLink>
            ))}
            {venueNav.length > 0 && useVenueDropdown(me) && (
              <NavVenueDropdown venues={venueNav} onNavigate={() => setOpen(false)} />
            )}
          </nav>
          <hr style={{ borderColor: 'rgba(255,255,255,0.1)' }} />
          <p className="sidebar-user">Logged in as {me.username}</p>
        </div>
      </aside>
      <main className="main">
        <div className="top-actions">
          <div className="app-brand">
            <img src="/vms-logo.png" alt="VMS" className="app-logo" />
            <h1 className="app-title">VENUE MANAGEMENT SYSTEM</h1>
          </div>
          <div className="top-actions-buttons">
            <Link className="btn btn-secondary" to="/profile">Edit Profile</Link>
            <button
              type="button"
              className="btn btn-danger"
              onClick={async () => {
                await logout()
                navigate('/login', { replace: true })
              }}
            >
              Logout
            </button>
          </div>
        </div>
        <Outlet />
      </main>
    </div>
  )
}

export function RequireAuth({ children }: { children?: ReactNode }) {
  const { me, loading } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    if (!loading && !me) navigate('/login', { replace: true })
    if (!loading && me?.mustChangePassword) navigate('/change-password', { replace: true })
  }, [loading, me, navigate])

  if (loading) return <div className="login-page muted">Loading…</div>
  if (!me) return null
  return <>{children ?? <Outlet />}</>
}
