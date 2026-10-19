import type {
  AuthStatus,
  DeletionResult,
  ImportPreview,
  ImportResult,
  CompletionDto,
  ErrorDto,
  HomeView,
  MemoryDto,
  MemoryStatus,
  ModelCatalog,
  SessionDetailDto,
  SessionDto,
  SessionListItemDto,
  SettingsDto,
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

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
    credentials: 'same-origin',
  })
  if (res.status === 204) return undefined as T
  const text = await res.text()
  const json = text ? JSON.parse(text) : null
  if (!res.ok) {
    const err: ErrorDto = json && typeof json === 'object' && 'message' in json
      ? (json as ErrorDto)
      : { message: `요청 실패 (${res.status})`, kind: 'HTTP' }
    throw new ApiError(res.status, err)
  }
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

export const api = {
  home: () => request<HomeView>('GET', '/api/home'),

  createSession: (checkIn: CheckInInput | null) =>
    request<SessionDto>('POST', '/api/sessions', { checkIn }),
  listSessions: (limit = 50, offset = 0) =>
    request<SessionListItemDto[]>('GET', `/api/sessions?limit=${limit}&offset=${offset}`),
  activeSession: () => request<SessionDto | undefined>('GET', '/api/sessions/active'),
  session: (id: number) => request<SessionDetailDto>('GET', `/api/sessions/${id}`),
  completeSession: (id: number) => request<CompletionDto>('POST', `/api/sessions/${id}/complete`),
  deleteSession: (id: number) => request<DeletionResult>('DELETE', `/api/sessions/${id}`),
  deleteAllData: () => request<DeletionResult>('DELETE', '/api/data', { confirmation: 'DELETE' }),

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
}

async function upload<T>(path: string, file: File): Promise<T> {
  const form = new FormData()
  form.append('file', file, file.name)
  const res = await fetch(path, { method: 'POST', body: form, credentials: 'same-origin' })
  const text = await res.text()
  const json = text ? JSON.parse(text) : null
  if (!res.ok) {
    const err: ErrorDto = json && typeof json === 'object' && 'message' in json
      ? (json as ErrorDto)
      : { message: `업로드 실패 (${res.status})`, kind: 'HTTP' }
    throw new ApiError(res.status, err)
  }
  return json as T
}

export const importApi = {
  preview: (file: File) => upload<ImportPreview>('/api/imports/preview', file),
  importArchive: (file: File) => upload<ImportResult>('/api/imports', file),
}

export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message
  if (e instanceof Error) return e.message
  return '알 수 없는 오류'
}

/** Official link for ChatGPT plan usage management (per OpenAI UX guidelines). */
export const CHATGPT_USAGE_URL = 'https://chatgpt.com/settings/usage'
