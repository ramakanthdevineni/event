import { useAuth } from '../auth'

export function DashboardPage() {
  const { me } = useAuth()
  if (!me) return null
  return (
    <div className="card">
      <h1 style={{ margin: '0 0 0.5rem' }}>Welcome, {me.firstName}</h1>
      <p className="muted" style={{ margin: 0 }}>
        {me.lastLoginAt ? `Last login: ${formatWhen(me.lastLoginAt)}` : 'No previous login recorded.'}
      </p>
    </div>
  )
}

function formatWhen(iso: string) {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  return d.toLocaleString()
}
