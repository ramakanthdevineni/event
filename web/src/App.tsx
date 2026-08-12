import { Navigate, Route, Routes } from 'react-router-dom'
import { useAuth } from './auth'
import { AppLayout, RequireAuth } from './layout'
import { AddUserPage } from './pages/AddUserPage'
import { AdminPage } from './pages/AdminPage'
import { ChangePasswordPage } from './pages/ChangePasswordPage'
import { DashboardPage } from './pages/DashboardPage'
import { LoginPage } from './pages/LoginPage'
import { MapviewPage } from './pages/MapviewPage'
import { ProfilePage } from './pages/ProfilePage'
import { StatusPage } from './pages/StatusPage'
import { UsersPage } from './pages/UsersPage'
import { VenuePage } from './pages/VenuePage'
import { LogsPage } from './pages/LogsPage'

function HomeRedirect() {
  const { me, loading } = useAuth()
  if (loading) return <div className="login-page muted">Loading…</div>
  if (!me) return <Navigate to="/login" replace />
  if (me.mustChangePassword) return <Navigate to="/change-password" replace />
  return <Navigate to={me.homePath} replace />
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/change-password"
        element={
          <RequireAuth>
            <ChangePasswordPage />
          </RequireAuth>
        }
      />
      <Route
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route path="/dashboard" element={<DashboardPage />} />
        <Route path="/users" element={<UsersPage />} />
        <Route path="/users/add" element={<AddUserPage />} />
        <Route path="/admin" element={<AdminPage />} />
        <Route path="/status" element={<StatusPage />} />
        <Route path="/logs" element={<LogsPage />} />
        <Route path="/reports" element={<Navigate to="/logs" replace />} />
        <Route path="/mapview" element={<MapviewPage />} />
        <Route path="/venues/:id" element={<VenuePage />} />
        <Route path="/profile" element={<ProfilePage />} />
      </Route>
      <Route path="/" element={<HomeRedirect />} />
      <Route path="*" element={<HomeRedirect />} />
    </Routes>
  )
}
