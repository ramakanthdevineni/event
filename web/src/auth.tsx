import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, ApiError } from './api'
import type { Me } from './types'

type AuthState = {
  me: Me | null
  loading: boolean
  refresh: () => Promise<Me | null>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null)
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    try {
      const data = await api.get<Me>('/api/me')
      setMe(data)
      return data
    } catch (err) {
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) {
        setMe(null)
        return null
      }
      setMe(null)
      return null
    } finally {
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.post('/api/logout')
    } catch {
      /* ignore */
    }
    setMe(null)
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const value = useMemo(() => ({ me, loading, refresh, logout }), [me, loading, refresh, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth requires AuthProvider')
  return ctx
}
