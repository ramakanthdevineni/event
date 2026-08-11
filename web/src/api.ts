export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers || {}),
    },
    ...init,
  })
  const text = await res.text()
  let data: unknown = null
  if (text) {
    try {
      data = JSON.parse(text)
    } catch {
      data = { message: text }
    }
  }
  if (!res.ok) {
    const msg =
      data && typeof data === 'object' && data !== null && 'message' in data
        ? String((data as { message: unknown }).message)
        : res.statusText || 'Request failed'
    throw new ApiError(res.status, msg)
  }
  return data as T
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  delete: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'DELETE', body: body === undefined ? undefined : JSON.stringify(body) }),
}
