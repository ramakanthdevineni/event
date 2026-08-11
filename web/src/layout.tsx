import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link, NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from './auth'

export function AppLayout() {
  const { me, logout } = useAuth()
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const sideRef = useRef<HTMLElement>(null)

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
            {me.nav.map((item) => (
              <NavLink
                key={item.href}
                to={item.href}
                className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}
                onClick={() => setOpen(false)}
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <hr style={{ borderColor: 'rgba(255,255,255,0.1)' }} />
          <p className="sidebar-user">Logged in as {me.username}</p>
        </div>
      </aside>
      <main className="main">
        <div className="top-actions">
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
