# SECURITY.md — mentalcare

개인용 · 1인 · 로컬 실행 AI 상담/자기성찰 앱의 보안 모델입니다. 상용 서비스가 아니며 인증(로그인) 기능이 없으므로,
**"이 PC의 사용자만 접근할 수 있다"**는 전제를 지키는 것이 보안의 핵심입니다.

마지막 감사: 2026-10-19 (Phase 1 → Phase 1.5: Docker, Sign in with ChatGPT, 보안 감사)

## Threat Model

### 보호 대상 (자산)

| 자산 | 위치 | 민감도 |
|---|---|---|
| 상담 원문 (messages) | `data/mentalcare.db` | 매우 높음 |
| 상담 Summary (AI 요약, 사실/해석) | `data/mentalcare.db`, `data/sessions/YYYY/*.md` | 매우 높음 |
| Long-term Memory (사용자 발언 + AI 관찰) | `data/mentalcare.db` | 매우 높음 |
| Weekly Check-in 점수·메모 | `data/mentalcare.db` | 높음 |
| 프로필/목표 (app_settings) | `data/mentalcare.db` | 높음 |
| OAuth credential (access/refresh/ID token, issued client_id) | `data/credentials/chatgpt-auth.json` (암호화) | 매우 높음 |
| `OPENAI_API_KEY` (개발자 모드만) | 환경변수 / `.env` | 매우 높음 |

### 공격자 모델

- **원격 공격자**: 인터넷/LAN에서 이 PC로 접근을 시도. 네트워크 바인딩과 Docker 포트 매핑으로 차단.
- **악성 웹사이트**: 사용자의 브라우저에서 `http://127.0.0.1:8080`으로 요청을 보내려는 시도 (CSRF, DNS rebinding).
- **같은 PC의 다른 사용자/프로세스**: 파일 시스템 접근. OS 사용자 권한과 DPAPI로 제한.
- **악의적 데이터(prompt injection)**: 과거 상담/기억에 담긴 지시문이 모델 행동을 바꾸려는 시도.
- **의존성 공급망**: 취약한 라이브러리.

이 모델이 **다루지 않는 것**: 이미 이 Windows 계정으로 코드 실행 권한을 가진 공격자(그 경우 DPAPI도 복호화 가능), 물리적 디스크 탈취 후 오프라인 공격(디스크 암호화는 사용자 책임), OpenAI 측 내부 처리.

## Trust Boundary

```
┌───────────────── Local PC (trusted zone) ─────────────────┐
│  Browser (127.0.0.1)  ──HTTP/SSE──▶  Spring Boot (127.0.0.1:8080)   │
│       ▲   토큰 없음, 상태 객체만              │                              │
│       │                                      ├──▶ SQLite / Markdown (data/) │
│       │                                      └──▶ credentials/ (암호화)     │
└───────┼───────────────────────────────────────┼───────────────────────────┘
        │ 시스템 브라우저 (OAuth 승인)             │ HTTPS only, redirect 금지
        ▼                                        ▼
   auth.openai.com (authorize)        auth.openai.com (token/revoke/jwks)
                                      api.openai.com/v1 (responses, models, moderations)
```

- **Browser ↔ Backend**: 같은 PC. 백엔드는 `Host`/`Origin`/`Sec-Fetch-Site`를 검사해 loopback 요청만 허용. CORS 헤더 없음. 세션 쿠키 없음.
- **Backend ↔ Filesystem**: 백엔드가 유일한 쓰기 주체. 경로는 서버가 생성(날짜+세션 id). 사용자 입력이 경로에 쓰이지 않음.
- **Backend ↔ OpenAI**: 유일하게 허용된 외부 전송. `store:false`, `stream:true`, bearer 토큰은 메모리/암호화 파일에만.
- **Frontend JS**: refresh/access/ID 토큰을 절대 받지 않음. `localStorage`/`sessionStorage`/cookie 사용 없음(정적 검사 `npm run test:security`로 강제).

## Allowed Data Egress

| 대상 | 허용 | 내용 |
|---|---|---|
| `https://api.openai.com/v1/responses` | ✅ | 시스템 프롬프트, 프로필/목표, ACTIVE 기억, 최근 요약 N회(필드 제한), 체크인, 현재 세션 메시지 |
| `https://api.openai.com/v1/responses` (요약) | ✅ | 상담 날짜, 체크인, 기존 기억 목록(id+내용), 현재 세션 대화 전체 |
| `https://api.openai.com/v1/responses` (위기 평가) | ✅ | 최근 메시지 최대 6개 |
| `https://api.openai.com/v1/moderations` | ✅ | 사용자 메시지 1건 |
| `https://api.openai.com/v1/models` | ✅ | 본문 없음 (bearer만) |
| `https://auth.openai.com/*` | ✅ | OAuth 파라미터, 코드, 토큰, `ext_agent_host_id`(비밀 아님) |
| 그 외 (analytics, telemetry, crash reporting, CDN, 외부 로그 서버) | ❌ | 코드에 존재하지 않음. 프론트 번들은 자체 호스팅, 외부 폰트/스크립트 없음(CSP `default-src 'self'`) |

상세 흐름과 필드 목록: [docs/DATA_FLOW.md](docs/DATA_FLOW.md).

## Controls (구현된 대응)

| 영역 | 대응 |
|---|---|
| 네트워크 | `server.address=127.0.0.1` 기본. Docker는 `127.0.0.1:8080:8080` 포트 매핑. `MENTALCARE_ALLOWED_HOSTS`를 바꾸지 않는 한 LAN 접근 불가. |
| DNS rebinding / CSRF | `LocalRequestGuardFilter`: 경로에 매트릭스 구간(`;`)·백슬래시·인코딩된 구분자가 있으면 403(Spring이 `;…`를 떼고 라우팅하므로 원본 URI만 보던 검사의 우회 경로를 차단), Host가 loopback이 아니면 403, Origin이 loopback이 아니거나 `null`이면 403, `/api/*`에 `Sec-Fetch-Site: cross-site`면 403(OAuth callback GET 제외). CORS 미설정. |
| 브라우저 하드닝 | CSP(`script-src 'self'`, `frame-ancestors 'none'`, `object-src 'none'`), `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, COOP/CORP, `/api` 응답 `Cache-Control: no-store`. JSON 출력에서 `< > & '`를 `\uXXXX`로 이스케이프(그 외 문자는 그대로; 예전 버전은 비ASCII까지 이스케이프했으므로 검색은 두 표기를 모두 찾음). |
| XSS | React가 모든 문자열을 텍스트로 렌더링. `dangerouslySetInnerHTML`/`innerHTML`/`eval` 사용 금지(정적 검사). AI 응답과 Markdown은 HTML로 해석하지 않음(평문 `white-space: pre-wrap`). |
| 인증 (OpenAI) | Sign in with ChatGPT: 동적 등록(`dynamic_agent_client` → issued `client_id` 저장), Authorization Code + PKCE S256, `state`(상수시간 비교, 1회용, 10분 만료), `nonce`, ID 토큰 RS256/`iss`/`aud`/`exp`/`azp` 검증(JWKS는 discovery 문서에서), granted scope에 `chatgpt.tokens.use.direct`가 없으면 추론 차단, refresh token 회전, 로그아웃 시 revoke. |
| Credential 저장 | `data/credentials/chatgpt-auth.json` — Windows: DPAPI(현재 사용자) / 그 외: AES-256-GCM(키 파일 `.master.key`, 0600). 상담 DB와 분리. 원자적 쓰기. 다른 provider가 쓴 파일은 덮어쓰지 않음. 토큰은 Markdown·SQLite·로그·프론트에 절대 저장/전달되지 않음. |
| 과금 | API 키 fallback 없음(`openai.auth-mode`는 둘 중 하나). 플랜 한도(429 `subscription_sharing_usage_limit_exceeded`)·권한 없음·미지원 기능은 애플리케이션 오류로 사용자에게 표시하고 요청 중단. |
| 이관 파일 | 업로드는 브라우저 파일 선택 → multipart(2MB, .md/.txt)로만 받음. 파일 경로를 서버가 읽지 않음. 내용은 텍스트로만 파싱·표시(HTML 렌더링 없음)하고, 모델에는 `<<<DATA>>>` 블록으로만 전달. AI 호출 없음. |
| Prompt injection | 과거 기록/기억/프로필/체크인/이관 기록은 `<<<DATA … >>>` 블록 안에만 들어가고, 시스템 프롬프트(v2)가 이를 데이터로 선언. 구분자 유사 문자열은 중화. 사용자 메시지는 `user` role로만 전달. `system` role 메시지 항목은 쓰지 않음. |
| Context 최소화 | 전체 DB/Markdown/과거 원문/파일 경로/설정/자격증명은 컨텍스트에 포함되지 않음(테스트로 고정). 최근 요약 N회(기본 3), 기억 최대 30개, 요약 필드당 5항목, 현재 세션 메시지 60,000자·요약 전사 150,000자·기억 목록 60개 상한(초과분은 오래된 것부터 생략하고 생략 사실을 명시). |
| 동시성 | 세션별 잠금(`SessionLocks`): 같은 세션의 인사·응답·종료·삭제가 겹치면 BUSY/409로 거부. 전체 삭제는 전역 유지보수 잠금으로, 진행 중인 세션 작업·새 상담 생성·이관 가져오기와 서로 배타적(커밋까지 잠금 유지). |
| 스트림 타임아웃 | OpenAI 스트림은 헤더 이후에도 watchdog으로 감시: 90초 무응답 또는 총 600초 초과 시 스트림을 닫고 TIMEOUT 처리. |
| 실행 스크립트 | 시작/종료 스크립트는 **이 저장소의 jar 절대경로**를 명령줄에 가진 프로세스만 대상으로 함. 시작 절차는 저장소별 Mutex로 직렬화, PID 파일에 PID·포트·jar 경로를 기록하고 포트 응답 PID를 대조. |
| 이관 중복 | 같은 내용(SHA-256)의 파일을 다시 가져오면 409. 이관 기억이 "반복 관찰"로 자동 확정되는 경로를 차단. |
| 로깅 | 상담 원문·토큰·코드·URL·raw 요청/응답 본문을 로그하지 않음. 예외는 클래스명/종류/provider code만. 테스트가 센티넬 문자열로 로그 누출을 검증. |
| 파일 시스템 | Markdown 경로 = `markdownDir/YYYY/YYYY-MM-DD[-id].md` (서버 생성). 업로드는 이관 Markdown(2MB)과 백업 zip뿐이며 서버가 임의 경로를 읽거나 쓰지 않음. DB는 `data-dir` 내부 고정. |
| 백업/복원 | 백업 = `VACUUM INTO` 스냅샷 + Markdown + manifest를 zip으로(자격증명은 opt-in, 암호화된 파일 그대로). 복원은 전역 유지보수 잠금 아래에서만: zip 항목 이름 allow-list(`..`·절대경로·백슬래시 거부, 항목 수·크기 상한), manifest 형식/버전 검사, 추출된 DB에 `PRAGMA integrity_check`·필수 테이블 확인 후 **복원 전 스냅샷**을 `data/backups/`에 쓰고, 테이블을 한 트랜잭션에서 행 단위로 교체(라이브 DB 파일 교체·커넥션 풀 재시작 없음). Markdown은 옆 폴더에 미리 준비한 뒤 DB 교체와 같은 트랜잭션 안에서 폴더 이름 바꾸기로 교체하고, 실패 시 DB 롤백과 함께 원래 폴더를 되돌림(부분 복원 없음). 잠금은 작업 폴더 생성 실패를 포함한 모든 경로에서 해제. 준비 폴더는 만드는 도중 실패해도 즉시 삭제하고, 비정상 종료로 남은 `.sessions-restore-*`/`.sessions-before-restore-*` 폴더는 설정의 스냅샷 개수에 포함되며 전체 삭제 때 함께 지움. 자격증명 복원은 파일명 allow-list와 JSON 형태 검사 후 원자적 쓰기. |
| 초안 | 작성 중 텍스트는 브라우저 저장소가 아니라 상담 DB의 `drafts` 테이블(세션당 1건)에 저장. 전역 잠금(read)에 참여하고, 세션이 ACTIVE일 때만 한 문장(INSERT … WHERE EXISTS)으로 저장되어 종료·삭제 직후 잔여 초안이 생기지 않음. 모든 저장 요청은 증가하는 revision을 갖고 서버는 더 새로운 revision만 반영하므로, 늦게 도착한 요청이나 탭 종료 keepalive와의 순서 역전으로 예전 글이 되살아나지 않음(전송 시 tombstone). 브라우저는 요청을 직렬화하고, 서버가 확인한 뒤에만 저장 완료로 보며 실패 시 재시도. 전송·종료·삭제 시 제거. 컨텍스트·로그·Markdown에는 포함되지 않음. |
| 응답 재시도 | `retry`는 저장된 사용자 메시지를 재사용하고 중간에 끊긴 부분 응답은 FAILED로 퇴역(모델·화면 모두에서 제외). 이미 답변이 있으면 생성하지 않음. 안전 등급은 저장된 값을 재사용. |
| 종료 버튼 | `POST /api/system/shutdown`에 `{"ifIdle":true}`를 보내는 앱 내 종료는 전역 write lock을 얻을 수 있을 때만(=응답 생성·종료·가져오기·새 상담·백업·초안 저장 등 어떤 작업도 없을 때) 진행하고, 그 순간부터 모든 새 작업을 거부(closed 상태). 아니면 409. 스크립트는 기존대로 본문 없이 호출하며 즉시 새 작업을 막음. 상태 엔드포인트는 PID/시작 시각/포트/데이터 폴더만 노출(상담 데이터 없음). |
| SQL | 모든 쿼리 `JdbcClient` named parameter 바인딩. 동적 SQL 조립 없음. enum/정규식으로 입력 검증. |
| 의존성 | Spring Boot 3.5.16, sqlite-jdbc 3.53.4.0, nimbus-jose-jwt 10.10, jna-platform 5.19.1; vite 7, react-router 7(알려진 취약점 해결). 미사용 의존성 없음. |
| Docker | multi-stage, non-root(uid 1000), read-only rootfs, `cap_drop: ALL`, `no-new-privileges`, `.dockerignore`로 data/.env/*.db/archive 제외, build arg로 비밀 주입 없음. |
| Safety(상담) | Moderation·키워드는 신호일 뿐, 구조화된 위기 평가가 판정. Moderation을 쓸 수 없으면(ChatGPT 토큰의 `no_matching_rule` 등) 모든 메시지를 위기 평가로 검사해 키워드 없는 위험 표현도 평가. 신호 후 평가 실패는 ELEVATED, 신호 없는 검사 실패는 "위험 없음"과 구분된 UNAVAILABLE로 기록하고 화면에 안내(도움 연락처 포함). 미지원 경로는 토큰 갱신을 유발하지 않고, 실제 인증 만료만 갱신. 관용 표현 vs 명시적 위험을 테스트로 고정. 의학적 진단 시스템이 아님을 UI/프롬프트에 명시. |

## Risks

각 항목: 위험 / 공격 경로 / 영향 / 현재 대응 / 잔여 위험. 심각도는 로컬 1인 사용 전제 기준.

### R1. 로컬 API에 인증이 없음 — **High (잔여: Medium)**
- 공격 경로: 같은 PC의 다른 프로세스/사용자가 `127.0.0.1:8080`에 요청.
- 영향: 상담 기록 열람·조작, ChatGPT 플랜 사용량 소모.
- 대응: loopback 바인딩, Host/Origin/Sec-Fetch 가드, Docker 포트를 127.0.0.1로 제한.
- 잔여: 같은 PC에서 실행되는 모든 로컬 프로세스는 접근 가능. 공유 PC에서는 사용하지 말 것. (향후: 로컬 PIN/세션 토큰)

### R2. DNS rebinding / CSRF — **High → 대응됨 (잔여: Low)**
- 공격 경로: 악성 페이지가 `attacker.com → 127.0.0.1`로 재바인딩하거나 cross-site POST 전송. 2026-10-19 외부 리뷰에서 `/api;x/health` 같은 매트릭스 경로와 `Origin: null`로 가드를 우회할 수 있음이 확인되어 같은 날 수정(경로 형태 검사 추가, null origin 거부, 회귀 테스트).
- 영향: 상담 데이터 읽기/쓰기, 세션 종료 유발.
- 대응: 경로 형태 검사, Host 화이트리스트, Origin 검사(null 포함 거부), Sec-Fetch-Site 거부, CORS 없음, JSON 전용 요청 본문.
- 잔여: Sec-Fetch 헤더를 보내지 않는 구형 브라우저에서 단순 요청(simple request) 한정 CSRF 가능성. JSON Content-Type 요구로 실질 영향 낮음.

### R3. OAuth 토큰 유출 — **High → 대응됨 (잔여: Medium on Linux/Docker)**
- 공격 경로: credential 파일 읽기, 로그, 프론트 노출.
- 영향: 사용자의 ChatGPT 플랜으로 추론 요청 가능(대화 접근은 불가 — scope 범위 밖).
- 대응: Windows DPAPI(사용자 바인딩), 토큰은 백엔드 메모리/암호화 파일에만, 로그 금지, revoke on sign-out.
- 잔여: Linux/Docker에서는 키 파일 기반 AES이므로 `data/credentials/`를 읽을 수 있는 공격자는 복호화 가능. 호스트 디스크 암호화와 `data/` 권한(0700)으로 보완. Docker 볼륨 백업에 암호화 파일+키가 함께 포함됨.

### R4. 상담 데이터가 평문 SQLite/Markdown — **Medium (잔여: Medium)**
- 공격 경로: 파일 접근, 백업 유출.
- 대응: `data/` git-ignored, Docker 이미지에 미포함, 로컬 전용.
- 잔여: 디스크 암호화(BitLocker 등)와 백업 관리는 사용자 책임. Phase 3에서 at-rest 암호화 검토.

### R5. OpenAI로의 데이터 전송 — **Medium (잔여: Medium, 설계상 수용)**
- 공격 경로: 없음(의도된 전송). 단, 전송 범위가 넓어질 위험.
- 대응: `store:false`, 컨텍스트 필드 고정 및 테스트, 요약 요청만 현재 세션 전체 전송.
- 잔여: 현재 세션 대화는 전체가 OpenAI에 전달됨(필수). OpenAI의 데이터 처리 정책에 의존.

### R6. Prompt injection via 기록/기억 — **Medium → 대응됨 (잔여: Low)**
- 공격 경로: 과거 메시지에 "이전 지시를 무시…" 삽입 → 요약/기억에 남아 다음 상담에 전달.
- 영향: 상담 품질 저하. 데이터 유출 경로는 없음(모델에 도구/파일 접근 없음).
- 대응: 데이터 블록 구분, 시스템 프롬프트의 신뢰 경계 선언, 구분자 중화.
- 잔여: LLM은 지시를 100% 무시한다고 보장할 수 없음. 영향 범위가 "응답 내용"에 한정.

### R7. ChatGPT 플랜 사용량 소모 — **Low**
- 공격 경로: R1/R2 경유.
- 대응: 로컬 전용 가드, 한도 도달 시 중단, 자동 재시도 없음(스트림 1회).
- 잔여: 정상 사용 중 한도 도달 시 상담 중단(안내 표시).

### R8. 의존성 취약점 — **Low (지속 관리)**
- 대응: 감사 시점 최신 패치 버전 적용(`npm audit` 0건). Maven 측은 Spring Boot BOM 최신 3.5.x.
- 잔여: 주기적 재감사 필요. 자동 업데이트 없음.

### R9. Safety 오판 — **Medium (잔여: Medium, 본질적)**
- 공격 경로/원인: 관용 표현 과대 분류 또는 명백한 위험 누락.
- 대응: 2단계(신호 → 문맥 평가), 보수적 fallback, 테스트.
- 잔여: 의학적으로 검증된 시스템이 아님. 긴급 상황에서는 앱이 아니라 전문 기관에 연락해야 함(UI에 상시 표시).

### R11. 백업 파일 유출 — **Medium (잔여: Medium, 사용자 보관 책임)**
백업 zip과 `data/backups/`의 복원 전 스냅샷에는 상담 원문·요약·기억이 평문으로 들어 있습니다(DB 스냅샷). 자격증명은 기본 제외이고 포함해도 암호화 상태지만, 파일 자체는 암호화되지 않습니다. 전체 삭제는 기본으로 앱이 만든 스냅샷까지 지우고 남은 개수를 알려 주며, 개별 상담 삭제 화면은 스냅샷·백업에 남는다는 사실을 표시합니다.
대응: UI가 "다른 저장 장치/클라우드에 옮겨 두라"고 안내하고 자격증명 포함을 opt-in으로 둠. 백업 zip 자체의 암호화(비밀번호)는 Phase 3 후보.
복원은 현재 데이터를 통째로 바꾸므로 확인 모달 + `confirmation=RESTORE` + 복원 전 스냅샷으로 실수를 되돌릴 수 있게 함.

### R10. `OPENAI_AUTH_MODE=api-key` 개발자 모드 — **Low**
- 대응: 기본값 아님, 명시적 설정 필요, UI에 과금 경고 표시, 자동 fallback 없음.
- 잔여: 개발자가 `.env`에 키를 넣고 공유 PC에서 실행하는 경우.

## 보고/수정

개인 프로젝트이므로 별도 보안 신고 채널은 없습니다. 발견 사항은 저장소 이슈로 기록하고, 수정 후 이 문서의 "마지막 감사" 날짜와 Risks를 갱신합니다.
