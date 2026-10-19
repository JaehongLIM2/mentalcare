# mentalcare 설계 문서

개인용 AI 상담 · 자기성찰 웹 애플리케이션. 1인 · 로컬 실행 · 비상용.
핵심 목표는 **상담 → 기록 → 기억 정리 → 다음 상담에서 참고 → 장기 변화 확인**의 연속성이다.

---

## 1. 전체 Architecture

```
┌──────────────────────────────┐        ┌──────────────────────────────────────────┐
│  Browser (React + Vite)      │  HTTP  │  Spring Boot (localhost:8080)            │
│  - Chat UI (SSE 수신)        │◄──────►│  api        Controller (REST + SSE)       │
│  - History / Memory / Trends │        │  counseling SessionService, CounselingSvc │
└──────────────────────────────┘        │  context    CounselingContextBuilder      │
                                        │  memory     MemoryService                 │
                                        │  safety     SafetyService                 │
                                        │  export     MarkdownExporter              │
                                        │  prompt     PromptLoader (resources/prompts)│
                                        │  ai         LlmClient ──► OpenAiLlmClient │
                                        │  repository JdbcClient → SQLite           │
                                        └───────┬──────────────────────┬───────────┘
                                                │                      │
                                     data/mentalcare.db      data/sessions/YYYY/*.md
                                     (source of truth)       (사람이 읽는 Archive)
                                                │
                                        OpenAI API (store:false)
                                        - Responses API (상담: stream / 요약: structured)
                                        - Moderation API (safety signal)
```

- 단일 Spring Boot 프로세스 + 단일 SQLite 파일. 인증 없음, `localhost` 바인딩만.
- 개발 시: Vite dev server(5173)가 `/api`를 8080으로 proxy.
- 운영 시: `npm run build` 결과를 `backend/src/main/resources/static`에 넣고 jar 하나로 실행.
- OpenAI 관련 코드는 `ai.openai` 패키지 안에만 존재한다. 비즈니스 로직은 `LlmClient` 인터페이스만 본다.

## 2. Backend Package Structure (`com.mentalcare`)

```
com.mentalcare
├── MentalCareApplication
├── config/        AppProperties(mentalcare.*), OpenAiProperties(openai.*), AsyncConfig
├── domain/        Session, Message, WeeklyCheckIn, SessionSummary, Memory, Tag,
│                  PromptVersion, SafetyEvent, ApiUsage + enum(SessionStatus, MessageRole,
│                  MemoryStatus, MemoryKind, RiskLevel)
├── repository/    SessionRepository, MessageRepository, CheckInRepository,
│                  SummaryRepository, MemoryRepository, TagRepository,
│                  PromptVersionRepository, SafetyEventRepository, UsageRepository,
│                  SettingsRepository              (모두 Spring JdbcClient 기반)
├── ai/            LlmClient(인터페이스), LlmException, StreamHandler, LlmUsage,
│                  ModerationSignal
│   ├── model/     SessionSummaryOutput, MemoryCandidateOutput, CrisisAssessmentOutput
│   └── openai/    OpenAiLlmClient, OpenAiHttp, SseEventReader
├── prompt/        PromptLoader (resources/prompts/*-vN.md 로드, hash, 버전 등록)
├── context/       CounselingContextBuilder, CounselingContext, ContextMessage
├── counseling/    SessionService(생성/조회/재개), CounselingService(메시지→안전→컨텍스트→스트림),
│                  SessionCompletionService(요약→메모리→태그→Markdown→COMPLETED)
├── memory/        MemoryService (후보 수집·중복 병합·상태 변경·ACTIVE 조회)
├── safety/        SafetyService, SafetyDecision, CrisisKeywordDetector
├── export/        MarkdownExporter
├── trend/         TrendService
├── usage/         UsageRecorder
└── api/           SessionController, MessageStreamController, MemoryController,
                   TrendController, HomeController, SettingsController,
                   dto/*, ApiExceptionHandler
```

책임 분리 원칙
- Controller: 요청 검증 + Service 호출 + DTO 변환만.
- Service: 흐름 제어. OpenAI, SQLite 세부사항을 모른다.
- `ai.openai.*` 외부에서는 OpenAI 요청/응답 JSON을 다루지 않는다.
- 상담 원문은 로그에 남기지 않는다(usage/모델/타입/시간만 기록).

## 3. Frontend Directory Structure (`frontend/`)

```
frontend/src
├── main.tsx, App.tsx          (react-router)
├── api/
│   ├── client.ts              fetch wrapper
│   ├── stream.ts              SSE(POST) 파서 → delta 콜백
│   └── types.ts               API DTO 타입
├── pages/
│   ├── HomePage.tsx           최근 상담/권장 시점/최근 체크인/추세 미니/목표
│   ├── NewSessionPage.tsx     Weekly Check-in(선택 입력) → 세션 생성
│   ├── SessionPage.tsx        Chat UI(스트리밍) + 상담 종료
│   ├── HistoryPage.tsx        날짜별 목록(주제, 태그, 요약)
│   ├── SessionDetailPage.tsx  [원본 대화] / [AI 요약] 탭 분리
│   ├── TrendsPage.tsx         체크인 5개 지표 라인 차트
│   ├── MemoryPage.tsx         Long-term Memory 확인/수정/보관
│   └── SettingsPage.tsx       모델/설정 확인, 사용량
├── components/  Layout, ChatMessage, CheckInForm, SummaryView, LineChart,
│                MemoryCard, SafetyBanner
└── styles/global.css          "상담실 + 회고 노트" 톤의 차분한 UI
```

## 4. SQLite Schema

```sql
sessions           (id, status[ACTIVE|COMPLETED], title, started_at, ended_at,
                    prompt_version_id, crisis_flag, created_at, updated_at)
messages           (id, session_id, role[USER|ASSISTANT], content, status[COMPLETE|INTERRUPTED|FAILED],
                    risk_level, created_at)
weekly_checkins    (id, session_id UNIQUE, mood, stress, anxiety, energy, sleep, note, created_at)
                    -- 각 점수 1~10 또는 NULL(건너뜀)
session_summaries  (id, session_id UNIQUE, summary_json, model, prompt_version_id,
                    markdown_path, created_at)
memories           (id, content, kind[USER_STATED|AI_OBSERVED], category, status[CANDIDATE|ACTIVE|ARCHIVED],
                    first_observed_session_id, last_observed_session_id, observation_count,
                    created_at, updated_at)
memory_observations(memory_id, session_id, note, created_at, PK(memory_id, session_id))
tags               (id, name UNIQUE)
session_tags       (session_id, tag_id, PK(session_id, tag_id))
prompt_versions    (id, name, version, content_hash, loaded_at, UNIQUE(name, version, content_hash))
safety_events      (id, session_id, message_id, moderation_flagged, moderation_scores_json,
                    risk_level, assessment_reason, created_at)
api_usage          (id, request_type, model, input_tokens, output_tokens, session_id, created_at)
app_settings       (key PRIMARY KEY, value)
```

- 스키마는 `schema.sql`(`CREATE TABLE IF NOT EXISTS`)로 초기화. 개인용 규모에서는 마이그레이션 도구를 두지 않는다.
- `summary_json`은 Structured Output 결과 원본(JSON). 화면과 Markdown은 이 JSON에서 생성.

## 5. Domain Model

| Domain | 핵심 필드 | 비고 |
|---|---|---|
| Session | id, status, title, startedAt, endedAt, promptVersionId, crisisFlag | 브라우저를 닫아도 ACTIVE 세션은 재개 가능 |
| Message | id, sessionId, role, content, status, riskLevel, createdAt | 사용자 메시지는 AI 호출 전에 먼저 저장 |
| WeeklyCheckIn | sessionId, mood, stress, anxiety, energy, sleep, note | 전부 선택 |
| SessionSummary | sessionId, output(SessionSummaryOutput), model, markdownPath | 사실(keyEvents/userThoughts) vs 해석(observedPatterns) 분리 |
| Memory | id, content, kind, category, status, firstObserved, lastObserved, observationCount | AI 해석은 kind=AI_OBSERVED로 구분 저장 |
| Tag | id, name | 소문자 영문 slug |
| PromptVersion | name, version, contentHash | 세션에 사용한 프롬프트 버전 추적 |
| SafetyEvent | sessionId, messageId, flagged, scores, riskLevel, reason | 점수와 판정만 저장, 원문 없음 |
| ApiUsage | requestType, model, inputTokens, outputTokens | 비용 확인용 |

Structured Output 모델(`ai.model`)
```
SessionSummaryOutput {
  title, topics[], keyEvents[], emotions[], userThoughts[], observedPatterns[],
  discoveries[], actionItems[], nextSessionTopics[],
  memoryCandidates[]: { content, kind, category, reason, matchesExistingMemoryId|null },
  tags[]
}
CrisisAssessmentOutput { riskLevel[NONE|LOW|ELEVATED|IMMINENT], reason, recommendedAction }
```

## 6. REST API

| Method | Path | 설명 |
|---|---|---|
| GET | /api/home | Home 대시보드(최근 상담, 권장 시점, 최근 체크인, 추세 요약, 목표) |
| POST | /api/sessions | 새 상담 생성(body: check-in, 모두 optional). ACTIVE 세션이 있으면 409 + 기존 id |
| GET | /api/sessions | 상담 목록(날짜, 제목, 태그, 요약 한 줄) |
| GET | /api/sessions/active | 진행 중 세션(없으면 204) |
| GET | /api/sessions/{id} | 세션 + 체크인 + 메시지 + 요약 |
| POST | /api/sessions/{id}/opening | **SSE** – 체크인 후 AI 첫 인사 스트리밍 |
| POST | /api/sessions/{id}/messages | **SSE** – 사용자 메시지 저장 후 AI 응답 스트리밍 |
| POST | /api/sessions/{id}/complete | 상담 종료 → 요약/메모리/Markdown 생성 → COMPLETED |
| GET | /api/memories?status= | Memory 목록(기본: CANDIDATE+ACTIVE) |
| PATCH | /api/memories/{id} | content / status 수정 |
| GET | /api/trends?limit=12 | 체크인 추세 |
| GET | /api/settings | 모델/설정/사용량 요약 |
| PATCH | /api/settings | reasoning effort, recent count 등 런타임 설정 |

SSE 이벤트(둘 다 동일)
```
event: user_saved   data: {"messageId":12}            (messages 엔드포인트만)
event: safety       data: {"riskLevel":"ELEVATED"}    (NONE이 아닐 때만)
event: delta        data: {"text":"..."}
event: done         data: {"messageId":13,"content":"...전체..."}
event: error        data: {"message":"..."}
```

## 7. OpenAI API 호출 흐름

| 작업 | 모델(설정키) | 방식 | 옵션 |
|---|---|---|---|
| 상담 응답 | `openai.counseling-model` (gpt-6.1-sol) | Responses API, stream | reasoning=medium(설정), store=false |
| 상담 요약 + Memory 후보 | `openai.utility-model` (gpt-6-luna) | Responses API, Structured Output(strict) | reasoning=low, store=false |
| Crisis Assessment | counseling-model | Responses API, Structured Output(strict) | store=false |
| Moderation | `openai.moderation-model` (omni-moderation-latest) | Moderation API | – |

- `LlmClient` 메서드: `streamCounselingResponse`, `generateSessionSummary`, `assessCrisis`, `moderate`.
- 요약과 Memory 후보 추출은 **요청 1회**로 처리(스키마에 `memoryCandidates` 포함). 후보 중복 판단을 돕기 위해 기존 CANDIDATE/ACTIVE Memory 목록(id+content)을 함께 전달하고, 모델이 `matchesExistingMemoryId`로 기존 항목과 연결한다.
- Structured Output 예외: refusal → `LlmException(REFUSAL)`, `status=incomplete` → `INCOMPLETE`, JSON 파싱/검증 실패 → `PARSE_FAILED`, HTTP 429/5xx → `RATE_LIMIT`/`SERVER`, timeout → `TIMEOUT`. 세션 종료는 요약 실패 시 세션을 ACTIVE로 유지하고 오류를 돌려주어 재시도 가능.
- 모든 요청의 usage(input/output tokens, model, type)는 `api_usage`에 기록. 본문은 기록하지 않는다.

## 8. Streaming 구조

```
Browser ──POST /api/sessions/{id}/messages──► Spring MVC (SseEmitter)
                                                │ 1. 사용자 메시지 저장(먼저)
                                                │ 2. SafetyService 평가
                                                │ 3. ContextBuilder → CounselingContext
                                                │ 4. OpenAiLlmClient.stream (JDK HttpClient, BodyHandlers.ofLines)
                                                │       response.output_text.delta → emitter.send(delta)
                                                │       response.completed       → usage 기록
                                                │ 5. ASSISTANT 메시지 저장(COMPLETE / INTERRUPTED)
                                                └──► emitter: done | error
```
- 브라우저가 끊겨도 서버는 스트림을 끝까지 받아 저장한다(메시지 유실 방지).
- 프론트는 `fetch` + `ReadableStream`으로 SSE를 파싱(POST body가 필요하므로 EventSource 미사용).
- 별도 라이브러리 없이 JDK 21 `HttpClient`로 OpenAI SSE 수신 → 의존성 최소화.

## 9. Memory 구조

```
Session Memory   = 현재 세션의 messages 전체
Recent Memory    = 최근 COMPLETED 세션 N개(기본 3, 설정)의 summary_json 핵심 필드
Long-term Memory = memories(status=ACTIVE)
```
상태 전이
```
요약 시 후보 추출 ──► CANDIDATE (observation_count=1)
  ├─ 같은 내용이 다른 세션에서 재관찰 → observation_count++, last_observed 갱신
  │     → USER_STATED: 2회 이상 / AI_OBSERVED: 3회 이상 관찰되면 자동 ACTIVE (설정값)
  ├─ 사용자가 Memory 화면에서 ACTIVE / ARCHIVED 로 직접 변경
  └─ ARCHIVED 는 컨텍스트에서 제외
```
- 컨텍스트에 넣는 Memory는 ACTIVE만. 후보는 사용자에게 보이지만 AI에게는 전달하지 않는다(요약 요청 시 중복판단 용도로만 전달).
- `kind`로 "사용자가 말한 사실"과 "AI의 해석"을 항상 구분한다.

## 10. Safety 흐름

```
사용자 메시지 저장
   │
   ├─ Moderation API (self-harm, self-harm/intent, self-harm/instructions, violence)
   ├─ 한국어/일본어 위기 키워드 휴리스틱 (보조 신호)
   │
   ▼ 신호 없음 ───────────────► NONE → 일반 상담
   ▼ 신호 있음
   Crisis Assessment (counseling-model, structured, 최근 메시지 몇 개 문맥 포함)
   │
   ├─ NONE / LOW      → 일반 상담
   ├─ ELEVATED        → 일반 상담 + "안전 확인 우선" 지시문 추가, 화면에 도움 안내 배너
   └─ IMMINENT        → 이번 턴부터 crisis-response 프롬프트로 전환, 세션 crisis_flag,
                        화면에 긴급 연락처 배너
```
- Moderation 단독으로 모드 전환하지 않는다. 관용 표현("죽겠다")은 Assessment가 문맥으로 거른다.
- Moderation/Assessment 호출 실패 시: 키워드 신호만으로 ELEVATED 처리(안전 쪽으로 보수적 fallback), 상담은 계속.
- safety_events에는 점수·판정·사유만 저장한다.

## 11. 상담 Sequence (시작 → 종료)

```
1. Home → "새 상담"  (ACTIVE 세션이 있으면 "이어하기")
2. Weekly Check-in 입력(모두 선택) → POST /api/sessions → 세션 ACTIVE, prompt_version 기록
3. POST /opening (SSE): Context = system prompt + profile + ACTIVE memory + recent 3 summaries + check-in
   → AI 첫 인사 + 첫 질문 → ASSISTANT 메시지 저장
4. 반복: 사용자 입력 → POST /messages (SSE)
   → USER 저장 → Safety → Context 재구성(세션 메시지 포함) → 스트리밍 → ASSISTANT 저장
5. 사용자가 "오늘은 여기까지" 등 종료 의사 → AI가 마무리 멘트 + 화면에서 종료 버튼 안내
6. POST /complete
   → 요약 요청(gpt-6-luna, structured) → session_summaries 저장
   → tags 저장 → MemoryService.ingest(memoryCandidates)
   → MarkdownExporter → data/sessions/YYYY/YYYY-MM-DD.md
   → session COMPLETED
   (요약 단계 실패 시 세션은 ACTIVE 유지, 사용자가 재시도)
7. Session Detail에서 원본 대화 / 요약 확인, Memory 화면에서 후보 검토
```

## 12. Phase 1.5 추가 설계 — 인증 · 보안 · Docker (2026-10-19)

### 인증: Sign in with ChatGPT (`com.mentalcare.auth`)

```
Browser ──POST /api/auth/chatgpt/start──► ChatGptAuthService.start()
   │   {authorizationUrl}                    ├─ OidcDiscovery (.well-known, endpoint origin 검증)
   │                                         ├─ Pkce (verifier/challenge/state/nonce)
   ▼                                         └─ pending[state] (10분)
auth.openai.com/api/accounts/authorize?client_id=dynamic_agent_client|<issued>&ext_agent_host_id=…&code_challenge=…
   │ 사용자 승인
   ▼
GET /api/auth/chatgpt/callback?code&state&client_id&scope ──► handleCallback()
   ├─ state 상수시간 비교 · access_denied · issued client_id 검증
   ├─ POST token (authorization_code + code_verifier + resource)
   ├─ IdTokenVerifier (RS256/JWKS, iss, aud, exp, nonce, azp, sub)
   ├─ scopes ∋ chatgpt.tokens.use.direct ? planUsage : 로그인만
   └─ CredentialStore.save(ChatGptAccount)  → 302 returnTo?chatgpt=connected
```

- `BearerTokenProvider` 인터페이스: `ChatGptTokenProvider`(기본) 또는 `ApiKeyTokenProvider`(`openai.auth-mode=api-key`). 둘 사이 fallback 없음.
- `HttpOpenAiTransport`는 요청마다 provider에서 bearer를 받음(만료 90초 전 자동 refresh, 401이면 다음 호출에서 강제 refresh).
- `ModelCatalogService`: `GET /v1/models`(visibility=list) 캐시 10분 → 설정 선호값이 없으면 첫 번째 가용 모델. 크래시 없음.
- Credential: `data/credentials/chatgpt-auth.json` = `{version, provider, ciphertext}`; Windows DPAPI / AES-GCM 키 파일. 상담 DB와 분리.

### 보안 레이어

- `LocalRequestGuardFilter`(최우선): Host 화이트리스트(127.0.0.1/localhost/[::1]), Origin 검사, `/api` cross-site 거부(callback GET 제외).
- `SecurityHeadersFilter`: CSP(`script-src 'self'`), nosniff, DENY, no-referrer, COOP/CORP, `/api` no-store.
- `JsonHardeningConfig`: JSON 출력에서 `< > & '` 이스케이프.
- `CounselingContextBuilder`: 사용자 유래 데이터는 `<<<DATA 라벨 … >>>` 블록에만, 시스템 프롬프트 v2가 신뢰 경계 선언.
- 로그: 본문/토큰/URL 금지, 예외는 클래스명·kind·provider code만.

### Docker

```
Dockerfile (multi-stage): node:22-alpine(frontend build) → maven:3.9-eclipse-temurin-21(jar) → eclipse-temurin:21-jre-alpine
  non-root uid 1000, read-only rootfs(+tmpfs /tmp), cap_drop ALL, no-new-privileges
docker-compose.yml: ports "127.0.0.1:8080:8080", volumes ./data:/data, env_file .env(optional)
```
컨테이너 내부는 `SERVER_ADDRESS=0.0.0.0`로 수신하지만 호스트 바인딩이 127.0.0.1로 제한되고 Host 가드가 그대로 적용된다.

## 설계상 단순화 결정

- Maven + Spring Boot 3.5, Spring JDBC(JdbcClient), SQLite(xerial). JPA 미사용.
- OpenAI SDK 미사용(JDK HttpClient + Jackson). API 형태 변경 시 `ai.openai`만 수정.
- 공식 Sign in with ChatGPT SDK(`@siwc/local`)는 Node 전용이라 미사용. 공식 문서의 프로토콜을 Java로 구현(`auth` 패키지), JWT 검증만 nimbus-jose-jwt 사용.
- Vector DB / Embedding 없음(Phase 2에서 평가).
- Docker, Redis, 메시지 큐 없음.
