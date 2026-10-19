import { useEffect, useState } from 'react'
import { api, CHATGPT_USAGE_URL, errorMessage } from '../api/client'
import type { AuthStatus } from '../api/types'

interface Props {
  compact?: boolean
  onChange?: (status: AuthStatus) => void
}

/**
 * "Continue with ChatGPT" card. The browser never sees tokens: it only receives the authorization
 * URL to navigate to, and afterwards a credential-free status object.
 */
export default function ChatGptConnect({ compact = false, onChange }: Props) {
  const [status, setStatus] = useState<AuthStatus | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const load = () =>
    api.auth
      .status()
      .then((s) => {
        setStatus(s)
        onChange?.(s)
      })
      .catch((e) => setError(errorMessage(e)))

  useEffect(() => {
    void load()
    const params = new URLSearchParams(window.location.search)
    const result = params.get('chatgpt')
    if (result === 'error') {
      const reason = params.get('reason') ?? ''
      setError(reasonText(reason))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const start = async (reconsent: boolean) => {
    setBusy(true)
    setError(null)
    try {
      const returnTo = window.location.origin + window.location.pathname
      const { authorizationUrl } = await api.auth.start(returnTo, reconsent)
      window.location.assign(authorizationUrl) // system browser = this browser; OpenAI redirects back to 127.0.0.1
    } catch (e) {
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  const signOut = async () => {
    if (!window.confirm('ChatGPT 연결을 해제할까요? 저장된 토큰이 삭제되고 OpenAI에 revoke 요청을 보냅니다.')) return
    setBusy(true)
    setError(null)
    try {
      const r = await api.auth.signOut()
      setNotice(
        r.revocationConfirmed
          ? '연결이 해제되었습니다.'
          : '로컬 토큰은 삭제했지만 OpenAI 측 revoke 확인에 실패했습니다. ChatGPT 설정에서 이 앱의 연결을 직접 해제할 수 있습니다.',
      )
      await load()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  if (!status && !error) return <div className="muted small">연결 상태 확인 중…</div>

  if (status && status.mode === 'API_KEY') {
    return (
      <div className="alert warn">
        개발자 모드(<code>OPENAI_AUTH_MODE=api-key</code>): 요청이 <code>OPENAI_API_KEY</code>로 과금됩니다. 일반 사용은 Sign in with ChatGPT를
        권장합니다.{status.apiKeyConfigured ? '' : ' (API 키가 설정되어 있지 않습니다.)'}
      </div>
    )
  }

  const connected = status?.connected ?? false
  const plan = status?.planUsage ?? false

  return (
    <div className={compact ? '' : 'card'}>
      {!compact && <h2>ChatGPT 계정</h2>}
      {error && <div className="alert error">{error}</div>}
      {notice && <div className="alert info">{notice}</div>}

      {!connected ? (
        <div className="stack">
          <p className="muted small">
            {status?.reauthRequired
              ? 'ChatGPT 연결이 만료되었거나 해제되었습니다. 다시 로그인하세요.'
              : '상담을 시작하려면 ChatGPT 계정으로 로그인하고 ChatGPT 플랜 사용을 승인하세요. API 키를 복사해 넣을 필요가 없습니다.'}
          </p>
          <button className="btn primary" onClick={() => start(false)} disabled={busy}>
            <ChatGptMark /> Continue with ChatGPT
          </button>
          <p className="muted small">
            AI 요청은 당신의 ChatGPT 플랜(Plus/Pro 등)의 사용량으로 처리됩니다. 이 앱 자체는 별도 과금이 없습니다.
          </p>
        </div>
      ) : (
        <div className="stack">
          <div className="row">
            <span className="badge active">연결됨</span>
            <span>{status?.email ?? status?.name ?? '계정'}</span>
            {plan ? (
              <span className="badge user">Using ChatGPT plan</span>
            ) : (
              <span className="badge ai">플랜 사용 권한 없음</span>
            )}
          </div>
          {!plan && (
            <div className="alert warn">
              ChatGPT 로그인은 되었지만 <b>ChatGPT 플랜 사용</b> 권한(<code>chatgpt.tokens.use.direct</code>)이 승인되지 않아 AI 상담을 사용할 수
              없습니다. 아래 버튼으로 권한을 다시 승인하세요. (API 키로 자동 전환하지 않습니다.)
              <div style={{ marginTop: 8 }}>
                <button className="btn primary sm" onClick={() => start(true)} disabled={busy}>
                  권한 다시 승인
                </button>
              </div>
            </div>
          )}
          <div className="row">
            <a className="btn sm" href={CHATGPT_USAGE_URL} target="_blank" rel="noreferrer noopener">
              Manage usage
            </a>
            <button className="btn sm" onClick={() => start(false)} disabled={busy}>
              다시 로그인
            </button>
            <button className="btn danger sm" onClick={signOut} disabled={busy}>
              연결 해제
            </button>
          </div>
          {!compact && (
            <div className="muted small">
              토큰 보관: {status?.credentialProvider} · <code>{status?.credentialsDir}</code>
              <br />
              호스트 ID: <code>{status?.hostId}</code> (비밀 아님, 기기 식별용)
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function reasonText(reason: string): string {
  switch (reason) {
    case 'access_denied':
      return 'ChatGPT 로그인이 취소되었습니다.'
    case 'state_mismatch':
      return '로그인 요청을 확인할 수 없었습니다(state 불일치). 다시 시도하세요.'
    case 'registration_incomplete':
      return 'OpenAI가 앱 등록 정보를 돌려주지 않았습니다. 다시 시도하세요.'
    case 'identity_mismatch':
      return '다른 ChatGPT 계정으로 로그인했습니다. 먼저 기존 연결을 해제하세요.'
    case 'invalid_id_token':
      return 'ID 토큰 검증에 실패해 로그인을 중단했습니다.'
    case 'token_exchange':
      return '인증 코드를 토큰으로 교환하지 못했습니다. 다시 시도하세요.'
    default:
      return `ChatGPT 로그인에 실패했습니다 (${reason || 'unknown'}).`
  }
}

function ChatGptMark() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" fill="currentColor">
      <path d="M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zm0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16zm-.5 4v5.4l3.7 2.2.8-1.3-3-1.8V8h-1.5z" />
    </svg>
  )
}
