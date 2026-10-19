import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, CHATGPT_USAGE_URL, errorMessage } from '../api/client'
import { streamTurn } from '../api/stream'
import type { MessageDto, RiskLevel, SessionDetailDto } from '../api/types'
import ChatMessage from '../components/ChatMessage'
import SafetyBanner from '../components/SafetyBanner'

const END_PHRASES = ['오늘은 여기까지', '상담 끝', '그만 이야기', '여기까지 할게', '이만 끝']

export default function SessionPage() {
  const { id } = useParams()
  const sessionId = Number(id)
  const navigate = useNavigate()

  const [detail, setDetail] = useState<SessionDetailDto | null>(null)
  const [messages, setMessages] = useState<MessageDto[]>([])
  const [streamingText, setStreamingText] = useState<string | null>(null)
  const [input, setInput] = useState('')
  const [busy, setBusy] = useState(false)
  const [completing, setCompleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [errorKind, setErrorKind] = useState<string | null>(null)
  const [risk, setRisk] = useState<RiskLevel | null>(null)
  const [endHint, setEndHint] = useState(false)
  const openingStarted = useRef(false)
  const bottomRef = useRef<HTMLDivElement>(null)

  const scrollDown = () => bottomRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' })

  const runTurn = useCallback(
    async (url: string, body: unknown | null) => {
      setBusy(true)
      setError(null)
      setStreamingText('')
      await streamTurn(url, body, {
        onUserSaved: () => {},
        onSafety: (level) => setRisk(level),
        onDelta: (t) => setStreamingText((prev) => (prev ?? '') + t),
        onDone: (m) => {
          setMessages((prev) => (prev.some((p) => p.id === m.id) ? prev : [...prev, m]))
          setStreamingText(null)
        },
        onError: (msg, kind) => {
          setError(msg)
          setErrorKind(kind ?? null)
          setStreamingText(null)
        },
      })
      setBusy(false)
      scrollDown()
    },
    [],
  )

  useEffect(() => {
    let cancelled = false
    api
      .session(sessionId)
      .then((d) => {
        if (cancelled) return
        if (d.session.status === 'COMPLETED') {
          navigate(`/history/${sessionId}`, { replace: true })
          return
        }
        setDetail(d)
        setMessages(d.messages)
        const lastUser = [...d.messages].reverse().find((m) => m.role === 'USER')
        if (lastUser?.riskLevel && lastUser.riskLevel !== 'NONE') setRisk(lastUser.riskLevel)
        if (d.messages.length === 0 && !openingStarted.current) {
          openingStarted.current = true
          void runTurn(`/api/sessions/${sessionId}/opening`, null)
        }
      })
      .catch((e) => setError(errorMessage(e)))
    return () => {
      cancelled = true
    }
  }, [sessionId, navigate, runTurn])

  useEffect(() => {
    scrollDown()
  }, [messages.length, streamingText])

  const send = async () => {
    const text = input.trim()
    if (!text || busy) return
    setInput('')
    const optimistic: MessageDto = {
      id: -Date.now(),
      role: 'USER',
      content: text,
      status: 'COMPLETE',
      createdAt: new Date().toISOString(),
    }
    setMessages((prev) => [...prev, optimistic])
    if (END_PHRASES.some((p) => text.includes(p))) setEndHint(true)
    await runTurn(`/api/sessions/${sessionId}/messages`, { content: text })
  }

  const complete = async () => {
    if (!window.confirm('상담을 종료하고 기록을 정리할까요? 요약 생성에 잠시 시간이 걸립니다.')) return
    setCompleting(true)
    setError(null)
    try {
      const result = await api.completeSession(sessionId)
      navigate(`/history/${sessionId}`, { replace: true, state: { completion: result } })
    } catch (e) {
      setError(errorMessage(e) + ' 상담은 그대로 유지됩니다. 잠시 후 다시 종료를 시도하세요.')
      setCompleting(false)
    }
  }

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      void send()
    }
  }

  if (!detail && !error) return <div className="muted">불러오는 중…</div>

  return (
    <div>
      <div className="spread page-head">
        <div>
          <h1>상담 중</h1>
          <p>
            {detail?.session.date} · 세션 #{sessionId}
            {detail?.checkIn && ' · 체크인 완료'}
          </p>
        </div>
        <div className="row">
          <Link className="btn sm" to="/">홈</Link>
          <button className="btn danger" onClick={complete} disabled={busy || completing}>
            {completing ? '기록을 정리하는 중…' : '상담 종료'}
          </button>
        </div>
      </div>

      <SafetyBanner level={risk} crisisFlag={detail?.session.crisisFlag ?? false} />
      {error && (
        <div className="alert error">
          {error}
          {errorKind === 'PLAN_LIMIT' && (
            <div style={{ marginTop: 6 }}>
              <a className="btn sm" href={CHATGPT_USAGE_URL} target="_blank" rel="noreferrer noopener">Manage usage</a>
            </div>
          )}
          {(errorKind === 'NOT_CONNECTED' || errorKind === 'AUTH_REQUIRED' || errorKind === 'NO_PLAN_USAGE') && (
            <div style={{ marginTop: 6 }}>
              <Link className="btn sm" to="/settings">설정에서 ChatGPT 연결</Link>
            </div>
          )}
        </div>
      )}
      {endHint && !busy && (
        <div className="alert info">
          오늘 상담을 마치려면 오른쪽 위의 <b>상담 종료</b> 버튼을 누르세요. 요약과 Markdown 기록이 생성됩니다.
        </div>
      )}

      <div className="chat">
        {messages.map((m) => (
          <ChatMessage key={m.id} role={m.role} content={m.content} createdAt={m.createdAt} status={m.status} />
        ))}
        {streamingText !== null && <ChatMessage role="ASSISTANT" content={streamingText || '…'} streaming />}
        <div ref={bottomRef} />
      </div>

      <div className="composer">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={onKey}
          placeholder="이번 주 이야기하고 싶은 것을 편하게 적어 주세요. (Ctrl+Enter 전송)"
          disabled={busy || completing}
          rows={3}
        />
        <div className="spread">
          <span className="muted small">
            상담자는 진단하지 않으며, 당신이 생각을 정리하도록 돕는 역할만 합니다.
            {' · '}Using ChatGPT plan · <a href={CHATGPT_USAGE_URL} target="_blank" rel="noreferrer noopener">Manage usage</a>
          </span>
          <button className="btn primary" onClick={send} disabled={busy || completing || !input.trim()}>
            {busy ? '답변 생성 중…' : '보내기'}
          </button>
        </div>
      </div>
    </div>
  )
}
