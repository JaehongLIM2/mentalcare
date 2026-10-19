// DTOs mirrored from backend com.mentalcare.api.dto.ApiDtos and auth/model endpoints.
// The backend omits null fields (Jackson non_null), hence the optional markers.

export type SessionStatus = 'ACTIVE' | 'COMPLETED'
export type MessageRole = 'USER' | 'ASSISTANT'
export type MessageStatus = 'COMPLETE' | 'INTERRUPTED' | 'FAILED'
export type RiskLevel = 'NONE' | 'LOW' | 'ELEVATED' | 'IMMINENT'
export type MemoryStatus = 'CANDIDATE' | 'ACTIVE' | 'ARCHIVED'
export type MemoryKind = 'USER_STATED' | 'AI_OBSERVED'
export type AuthMode = 'CHATGPT' | 'API_KEY'

export type SessionKind = 'COUNSELING' | 'IMPORTED'

export interface SessionDto {
  id: number
  status: SessionStatus
  kind: SessionKind
  title?: string | null
  startedAt: string
  endedAt?: string | null
  date: string
  crisisFlag: boolean
  promptVersion?: string | null
}

export interface CheckInDto {
  mood?: number | null
  stress?: number | null
  anxiety?: number | null
  energy?: number | null
  sleep?: number | null
  note?: string | null
}

export interface MessageDto {
  id: number
  role: MessageRole
  content: string
  status: MessageStatus
  riskLevel?: RiskLevel | null
  createdAt: string
  /** true on a user message whose safety check could not be done */
  safetyUnavailable?: boolean | null
}

export interface DraftDto {
  content: string
  updatedAt: string
}

export interface MemoryCandidate {
  content: string
  kind: MemoryKind
  category: string
  reason: string
  matchesExistingMemoryId?: number | null
}

export interface SummaryOutput {
  title: string
  topics: string[]
  keyEvents: string[]
  emotions: string[]
  userThoughts: string[]
  observedPatterns: string[]
  discoveries: string[]
  actionItems: string[]
  nextSessionTopics: string[]
  memoryCandidates: MemoryCandidate[]
  tags: string[]
}

export interface SummaryDto {
  output: SummaryOutput
  model: string
  markdownPath?: string | null
  createdAt: string
}

export interface SessionDetailDto {
  session: SessionDto
  checkIn?: CheckInDto | null
  messages: MessageDto[]
  summary?: SummaryDto | null
  tags: string[]
  importedMarkdown?: string | null
  draft?: DraftDto | null
}

export interface SessionListItemDto {
  session: SessionDto
  topics: string[]
  tags: string[]
  oneLine?: string | null
  checkIn?: CheckInDto | null
}

export interface SessionListPage {
  items: SessionListItemDto[]
  total: number
}

export interface TagCount {
  name: string
  count: number
}

export interface SessionFilter {
  q?: string
  from?: string
  to?: string
  tag?: string
}

export interface CompletionDto {
  session: SessionDto
  summary?: SummaryDto | null
  tags: string[]
  memoriesCreated: number
  memoriesMerged: number
  memoriesActivated: number
  warning?: string | null
}

export interface ObservationDto {
  sessionId: number
  sessionDate: string
  note?: string | null
}

export interface MemoryDto {
  id: number
  content: string
  kind: MemoryKind
  category: string
  status: MemoryStatus
  firstObservedSessionId?: number | null
  lastObservedSessionId?: number | null
  observationCount: number
  createdAt: string
  updatedAt: string
  observations: ObservationDto[]
  /** SUMMARY = extracted from a session, MANUAL = written by the user */
  source?: 'SUMMARY' | 'MANUAL'
}

/** A sentence from a recent summary that could become a memory (GET /api/memories/suggestions). */
export interface MemorySuggestion {
  key: string
  text: string
  category: string
  source: string
  sessionId: number
  sessionDate: string
  sessionTitle?: string | null
}

export interface TrendPoint {
  sessionId: number
  date: string
  mood?: number | null
  stress?: number | null
  anxiety?: number | null
  energy?: number | null
  sleep?: number | null
}

export interface HomeView {
  activeSessionId?: number | null
  lastSessionId?: number | null
  lastSessionDate?: string | null
  lastSessionTitle?: string | null
  daysSinceLast?: number | null
  recommendedNextDate?: string | null
  dueNow: boolean
  lastNextSessionTopics: string[]
  lastActionItems: string[]
  recentCheckIns: TrendPoint[]
  goals: string
  totalSessions: number
  activeMemories: number
  candidateMemories: number
  authMode: AuthMode
  aiReady: boolean
  aiUnavailableReason?: string | null
  usesChatGptPlan: boolean
}

export interface UsageDto {
  model: string
  requestType: string
  requests: number
  inputTokens: number
  outputTokens: number
}

export interface SettingsDto {
  authMode: AuthMode
  aiReady: boolean
  aiUnavailableReason?: string | null
  counselingModel: string
  utilityModel: string
  moderationModel: string
  defaultCounselingModel: string
  defaultUtilityModel: string
  counselingReasoningEffort: string
  defaultReasoningEffort: string
  recentSessionCount: number
  profileSummary: string
  goals: string
  dataDir: string
  markdownDir: string
  credentialsDir: string
  credentialProvider: string
  apiKeyConfigured: boolean
  welcomeShown: boolean
  usageLast30Days: UsageDto[]
  lastBackupAt?: string | null
  backupsDir: string
  restoreSnapshots: number
}

export interface AuthStatus {
  mode: AuthMode
  connected: boolean
  reauthRequired: boolean
  registered: boolean
  email?: string | null
  name?: string | null
  planUsage: boolean
  scopes: string[]
  expiresAt?: string | null
  hostId: string
  credentialProvider: string
  credentialsDir: string
  apiKeyConfigured: boolean
  storageError?: string | null
}

export interface ModelInfo {
  slug: string
  displayName: string
}

export interface ModelCatalog {
  available: ModelInfo[]
  counselingModel: string
  utilityModel: string
  defaultCounselingModel: string
  defaultUtilityModel: string
  catalogError?: string | null
  fromAccount: boolean
}

export interface ImportPreview {
  title?: string | null
  counseledAt?: string | null
  mainTopics?: string | null
  factCount: number
  eventCount: number
  relationshipCount: number
  concernCount: number
  aiPatternCount: number
  memoryCandidateCount: number
  coreContextChars: number
  warnings: string[]
  alreadyImportedSessionId?: number | null
}

export interface ImportResult {
  sessionId: number
  title: string
  date: string
  memoriesCreated: number
  memoriesMerged: number
  warnings: string[]
}

export interface DeletionResult {
  sessionsDeleted: number
  filesDeleted: number
  filesFailed: number
  /** Restore safety snapshots under data/backups removed by delete-all. */
  backupsDeleted: number
  /** Safety snapshots still present (they contain the deleted data). */
  backupsRemaining: number
}

/** What the counselor will refer to on the next turn (GET /api/sessions/{id}/context). */
export interface ContextOutline {
  profileIncluded: boolean
  goalsIncluded: boolean
  checkInIncluded: boolean
  memories: { id: number; content: string; kind: MemoryKind; category: string }[]
  activeMemoryTotal: number
  recentSummaries: { sessionId: number; date: string; title?: string | null; hasSummary: boolean }[]
  recentSummaryLimit: number
  importedArchives: { sessionId: number; date: string; title?: string | null; charsIncluded: number; truncated: boolean }[]
  messagesIncluded: number
  messagesOmitted: number
  charsIncluded: number
  charsBudget: number
}

export interface BackupInspection {
  createdAt?: string | null
  appVersion?: string | null
  sessions: number
  messages: number
  memories: number
  markdownFiles: number
  includesCredentials: boolean
  warnings: string[]
}

export interface RestoreResult {
  sessionsRestored: number
  markdownFilesRestored: number
  preRestoreBackup: string
  credentialsRestored: boolean
  warnings: string[]
}

export interface SystemStatus {
  status: string
  app: string
  version: string
  pid: number
  startedAt: string
  uptimeSeconds: number
  port: number
  dataDir: string
  busySessions: number
  docker: boolean
}

export interface ErrorDto {
  message: string
  kind?: string
  relatedId?: number | null
  fields?: Record<string, string>
}
