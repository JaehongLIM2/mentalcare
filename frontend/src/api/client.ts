import type {
  AuthStatus,
  BackupInspection,
  ContextOutline,
  DeletionResult,
  ImportPreview,
  ImportResult,
  CompletionDto,
  ErrorDto,
  HomeView,
  MemoryDto,
  MemoryStatus,
  ModelCatalog,
  RestoreResult,
  SessionDetailDto,
  SessionDto,
  SessionFilter,
  SessionListItemDto,
  SessionListPage,
  SettingsDto,
  SystemStatus,
  TagCount,
  TrendPoint,
} from './types'

export class ApiError extends Error {
  readonly status: number
  readonly body: ErrorDto

  constructor(status: number, body: ErrorDto) {
    super(body.message)
    this.status = status
    this.body = body
  }

  /** True when the user has to connect / sign in with ChatGPT before AI features work. */
  get requiresSignIn(): boolean {
    const k = this.body.kind
    return k === 'NOT_CONNECTED' || k === 'AUTH_REQUIRED' || k === 'NO_PLAN_USAGE' || k === 'NOT_CONFIGURED'
  }
}

function toError(status: number, json: unknown, fallback: string): ApiError {
  const err: ErrorDto = json && typeof json === 'object' && 'message' in json
    ? (json as ErrorDto)
    : { message: `${fallback} (${status})`, kind: 'HTTP' }
  return new ApiError(status, err)
}

async function requestRaw(method: string, path: string, body?: unknown, keepalive = false): Promise<Response> {
  return fetch(path, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
    keepalive,
  })
}

async function request<T>(method: string, path: string, body?: unknown, keepalive = false): Promise<T> {
  const res = await requestRaw(method, path, body, keepalive)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const json = text ? JSON.parse(text) : null
  if (!res.ok) throw toError(res.status, json, '요청 실패')
  return json as T
}

export interface CheckInInput {
  mood?: number | null
  stress?: number | null
  anxiety?: number | null
  energy?: number | null
  sleep?: number | null
  note?: string | null
}

function query(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

export const api = {
  home: () => request<HomeView>('GET', '/api/home'),

  createSession: (checkIn: CheckInInput | null) =>
    request<SessionDto>('POST', '/api/sessions', { checkIn }),
  listSessions: (limit = 50, offset = 0) =>
    request<SessionListItemDto[]>('GET', `/api/sessions?limit=${limit}&offset=${offset}`),
  /** History with keyword / date / tag filter; the total number of matches comes from a header. */
  searchSessions: async (filter: SessionFilter, limit = 50, offset = 0): Promise<SessionListPage> => {
    const res = await requestRaw('GET', `/api/sessions${query({ limit, offset, q: filter.q, from: filter.from, to: filter.to, tag: filter.tag })}`)
    const text = await res.text()
    const json = text ? JSON.parse(text) : null
    if (!res.ok) throw toError(res.status, json, '요청 실패')
    const total = Number(res.headers.get('X-Total-Count') ?? (json as SessionListItemDto[]).length)
    return { items: json as SessionListItemDto[], total: Number.isFinite(total) ? total : 0 }
  },
  tags: () => request<TagCount[]>('GET', '/api/sessions/tags'),
  activeSession: () => request<SessionDto | undefined>('GET', '/api/sessions/active'),
  session: (id: number) => request<SessionDetailDto>('GET', `/api/sessions/${id}`),
  sessionContext: (id: number) => request<ContextOutline>('GET', `/api/sessions/${id}/context`),
  /** Composer text; sent with keepalive when the tab is closing. Blank removes the draft. */
  saveDraft: (id: number, content: string, keepalive = false) =>
    request<void>('POST', `/api/sessions/${id}/draft`, { content }, keepalive),
  completeSession: (id: number) => request<CompletionDto>('POST', `/api/sessions/${id}/complete`),
  deleteSession: (id: number) => request<DeletionResult>('DELETE', `/api/sessions/${id}`),
  deleteAllData: (includeAppBackups = true) =>
    request<DeletionResult>('DELETE', '/api/data', { confirmation: 'DELETE', includeAppBackups }),

  memories: (status?: MemoryStatus[] | 'ALL') => {
    const q = status === undefined ? '' : status === 'ALL' ? '?status=ALL' : `?status=${status.join(',')}`
    return request<MemoryDto[]>('GET', `/api/memories${q}`)
  },
  updateMemory: (id: number, patch: { content?: string; category?: string; status?: MemoryStatus }) =>
    request<MemoryDto>('PATCH', `/api/memories/${id}`, patch),

  trends: (limit = 12) => request<TrendPoint[]>('GET', `/api/trends?limit=${limit}`),

  settings: () => request<SettingsDto>('GET', '/api/settings'),
  updateSettings: (patch: {
    counselingReasoningEffort?: string
    recentSessionCount?: number
    profileSummary?: string
    goals?: string
    counselingModel?: string
    utilityModel?: string
    welcomeShown?: boolean
  }) => request<SettingsDto>('PATCH', '/api/settings', patch),

  models: (refresh = false) => request<ModelCatalog>('GET', `/api/models${refresh ? '?refresh=true' : ''}`),

  auth: {
    status: () => request<AuthStatus>('GET', '/api/auth/chatgpt/status'),
    start: (returnTo: string, reconsent = false) =>
      request<{ authorizationUrl: string }>('POST', '/api/auth/chatgpt/start', { returnTo, reconsent }),
    signOut: () => request<{ revocationConfirmed: boolean }>('POST', '/api/auth/chatgpt/signout'),
    forget: () => request<void>('POST', '/api/auth/chatgpt/forget'),
  },

  system: {
    status: () => request<SystemStatus>('GET', '/api/system/status'),
    /** Quits the server only when no reply is being generated. */
    shutdown: () => request<{ status: string }>('POST', '/api/system/shutdown', { ifIdle: true }),
  },
}

async function upload<T>(path: string, file: File, fields: Record<string, string> = {}): Promise<T> {
  const form = new FormData()
  form.append('file', file, file.name)
  for (const [k, v] of Object.entries(fields)) form.append(k, v)
  const res = await fetch(path, { method: 'POST', body: form, credentials: 'same-origin' })
  const text = await res.text()
  const json = text ? JSON.parse(text) : null
  if (!res.ok) throw toError(res.status, json, '업로드 실패')
  return json as T
}

export const importApi = {
  preview: (file: File) => upload<ImportPreview>('/api/imports/preview', file),
  importArchive: (file: File) => upload<ImportResult>('/api/imports', file),
}

export const backupApi = {
  /** Downloads a backup zip through the browser's save dialog; the file name comes from the server. */
  download: async (includeCredentials: boolean): Promise<string> => {
    const res = await fetch(`/api/backup?credentials=${includeCredentials ? 'true' : 'false'}`, { credentials: 'same-origin' })
    if (!res.ok) {
      let json: unknown = null
      try {
        json = await res.json()
      } catch {
        // not JSON
      }
      throw toError(res.status, json, '백업 실패')
    }
    const disposition = res.headers.get('Content-Disposition') ?? ''
    const match = /filename="([^"]+)"/.exec(disposition)
    const name = match ? match[1] : 'mentalcare-backup.zip'
    const blob = await res.blob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
    return name
  },
  inspect: (file: File) => upload<BackupInspection>('/api/backup/inspect', file),
  restore: (file: File, credentials: boolean) =>
    upload<RestoreResult>('/api/backup/restore', file, { confirmation: 'RESTORE', credentials: credentials ? 'true' : 'false' }),
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return '알 수 없는 오류'
}

/** Official link for ChatGPT plan usage management (per OpenAI UX guidelines). */
export const CHATGPT_USAGE_URL = 'https://chatgpt.com/settings/usage'
