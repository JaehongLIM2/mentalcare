import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, CHATGPT_USAGE_URL, errorMessage } from '../api/client'
import { streamTurn } from '../api/stream'
import type { ContextOutline, MessageDto, RiskLevel, SessionDetailDto } from '../api/types'
import ChatMessage from '../components/ChatMessage'
import SafetyBanner from '../components/SafetyBanner'
import ConfirmDeleteModal from '../components/ConfirmDeleteModal'
import { fmtTime } from '../util/format'
import { DraftSaver } from '../util/draftSaver'
import { ACCEPT, addFiles, MAX_FILES, MAX_TOTAL_CHARS, readFile, type PendingFile } from '../util/attachments'
import { parseLineChat, type LineChat } from '../util/lineChat'
import LineRangeModal from '../components/LineRangeModal'

const END_PHRASES = ['오늘은 여기까지', '상담 끝', '그만 이야기', '여기까지 할게', '이만 끝']
const DRAFT_DEBOUNCE_MS = 800

/** Where a turn is: shown next to the composer so a lost connection is never mistaken for a lost message. */
type Phase = 'idle' | 'sending' | 'saved' | 'generating' | 'done' | 'interrupted'

const PHASE_LABEL: Record<Phase, string> = {
  idle: '',
  sending: '메시지 전송 중…',
  saved: '메시지 저장됨 · 답변 기다리는 중',
  generating: '답변 생성 중…',
  done: '답변 저장됨',
  interrupted: '연결 중단 · 답변을 받지 못했습니다',
}

/** Error kinds after which "ask again" makes no sense. */
const NO_RETRY_KINDS = new Set(['BUSY', 'CONFLICT', 'ALREADY_ANSWERED', 'BAD_REQUEST', 'NOT_CONNECTED', 'AUTH_REQUIRED', 'NO_PLAN_USAGE', 'NOT_CONFIGURED'])

function lastUsable(messages: MessageDto[]): MessageDto | undefined {
  for (let i = messages.length - 1; i >= 0; i--) {
    if (messages[i].status !== 'FAILED') return messages[i]
  }
  return undefined
}

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
  const [safetyUnchecked, setSafetyUnchecked] = useState(false)
  const [files, setFiles] = useState<PendingFile[]>([])
  const [fileError, setFileError] = useState<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [lineChat, setLineChat] = useState<{ name: string; chat: LineChat } | null>(null)
  const [endHint, setEndHint] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [phase, setPhase] = useState<Phase>('idle')
  const [canRetry, setCanRetry] = useState(false)
  const [draftNotice, setDraftNotice] = useState<string | null>(null)
  const [omitted, setOmitted] = useState(0)
  const [outline, setOutline] = useState<ContextOutline | null>(null)
  const [showContext, setShowContext] = useState(false)
  const openingStarted = useRef(false)
  const chatRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef(input)
  const doneTimer = useRef<number | null>(null)
  const showContextRef = useRef(showContext)
  inputRef.current = input
  showContextRef.current = showContext

  // ---------------------------------------------------------------- scrolling (the conversation has its own scroll area)
  // Follow new text only while the reader is at the bottom; reading further up is never interrupted.
  const stickToBottom = useRef(true)
  const autoScrollUntil = useRef(0)
  const scrollFrame = useRef<number | null>(null)
  const initialScrolled = useRef(false)
  const [newBelow, setNewBelow] = useState(false)

  const nearBottom = () => {
    const el = chatRef.current
    return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }
  const onChatScroll = () => {
    if (Date.now() < autoScrollUntil.current && !nearBottom()) return // our own smooth scroll is still moving
    stickToBottom.current = nearBottom()
    if (stickToBottom.current) setNewBelow(false)
  }
  const scrollToBottom = useCallback((smooth: boolean) => {
    const el = chatRef.current
    if (!el) return
    stickToBottom.current = true
    setNewBelow(false)
    if (smooth) {
      autoScrollUntil.current = Date.now() + 700
      el.scrollTo({ top: el.scrollHeight, behavior: 'smooth' })
    } else {
      el.scrollTop = el.scrollHeight
    }
  }, [])
  /** At most one instant scroll per frame while text streams in (no stacked smooth animations). */
  const followFrame = useCallback(() => {
    if (scrollFrame.current !== null) return
    scrollFrame.current = window.requestAnimationFrame(() => {
      scrollFrame.current = null
      const el = chatRef.current
      if (el && stickToBottom.current) el.scrollTop = el.scrollHeight
    })
  }, [])

  // ---------------------------------------------------------------- draft autosave (local backend, never browser storage)
  const [draftFailing, setDraftFailing] = useState(false)
  // The saver lives exactly as long as this effect: a new one per session, and a fresh one when React
  // re-runs the effect (StrictMode in development), so a disposed instance is never reused.
  const saverRef = useRef<DraftSaver | null>(null)
  useEffect(() => {
    const saver = new DraftSaver({
      // saved only once the server confirmed it; serialized; every request carries a newer revision
      save: (text, keepalive, rev) => api.saveDraft(sessionId, text, rev, keepalive),
      onStateChange: setDraftFailing,
    })
    saverRef.current = saver
    const onHide = () => void saver.flush(inputRef.current, true)
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') void saver.flush(inputRef.current, true)
    }
    window.addEventListener('pagehide', onHide)
    document.addEventListener('visibilitychange', onVisibility)
    return () => {
      window.removeEventListener('pagehide', onHide)
      document.removeEventListener('visibilitychange', onVisibility)
      void saver.flush(inputRef.current, true) // leaving the page through the app's own navigation
      saver.dispose()
      if (saverRef.current === saver) saverRef.current = null
    }
  }, [sessionId])

  useEffect(() => {
    const t = window.setTimeout(() => void saverRef.current?.flush(inputRef.current), DRAFT_DEBOUNCE_MS)
    return () => window.clearTimeout(t)
  }, [input, sessionId])

  // ---------------------------------------------------------------- context outline
  const loadOutline = useCallback(() => {
    api
      .sessionContext(sessionId)
      .then((o) => {
        setOutline(o)
        setOmitted(o.messagesOmitted)
      })
      .catch(() => setOutline(null))
  }, [sessionId])

  useEffect(() => {
    if (showContext) loadOutline()
  }, [showContext, loadOutline])

  // ---------------------------------------------------------------- turns
  const reconcile = useCallback(async () => {
    try {
      const d = await api.session(sessionId)
      setMessages(d.messages)
      const last = lastUsable(d.messages)
      if (last?.role === 'ASSISTANT' && last.status === 'COMPLETE') {
        // the server finished while we were disconnected
        setPhase('done')
        setError(null)
        setErrorKind(null)
        setCanRetry(false)
      } else {
        setCanRetry(true)
      }
    } catch {
      setCanRetry(true)
    }
  }, [sessionId])

  const runTurn = useCallback(
    async (url: string, body: unknown | null, pending?: { tempId: number; text: string; files?: PendingFile[] }) => {
      setBusy(true)
      setError(null)
      setErrorKind(null)
      setCanRetry(false)
      setStreamingText('')
      setPhase(pending ? 'sending' : 'generating')
      if (doneTimer.current) window.clearTimeout(doneTimer.current)
      let userSaved = false
      let failedKind: string | undefined
      let finished = false
      await streamTurn(url, body, {
        onUserSaved: (messageId) => {
          userSaved = true
          setSafetyUnchecked(false) // a newly saved message is checked afresh
          setPhase('saved')
          if (pending) {
            setMessages((prev) => prev.map((m) => (m.id === pending.tempId ? { ...m, id: messageId } : m)))
          }
        },
        onSafety: (level) => setRisk(level),
        onSafetyUnavailable: () => {
          setSafetyUnchecked(true)
          if (pending) {
            setMessages((prev) => prev.map((m) => (m.id === pending.tempId ? { ...m, safetyUnavailable: true } : m)))
          }
        },
        onContext: (_included, omittedCount) => setOmitted(omittedCount),
        onDelta: (t) => {
          setPhase('generating')
          setStreamingText((prev) => (prev ?? '') + t)
        },
        onDone: (m) => {
          finished = m.status === 'COMPLETE'
          setMessages((prev) => (prev.some((p) => p.id === m.id) ? prev : [...prev, m]))
          setStreamingText(null)
        },
        onError: (msg, kind) => {
          failedKind = kind
          if (pending && !userSaved) {
            // the server never stored it: take it off the screen and give the text back
            setMessages((prev) => prev.filter((m) => m.id !== pending.tempId))
            setInput((current) => (current.trim() ? current : pending.text))
            if (pending.files?.length) setFiles((current) => (current.length ? current : pending.files ?? []))
            setError(`${msg} 입력한 내용${pending.files?.length ? '과 첨부 파일' : ''}은 저장되지 않아 입력란에 되돌려 두었습니다.`)
          } else {
            setError(msg)
          }
          setErrorKind(kind ?? null)
          setStreamingText(null)
        },
      })
      setBusy(false)
      if (finished && !failedKind) {
        setPhase('done')
        doneTimer.current = window.setTimeout(() => setPhase('idle'), 4000)
        if (showContextRef.current) loadOutline()
      } else if (failedKind === 'ALREADY_ANSWERED') {
        await reconcile()
      } else if (failedKind && (userSaved || !pending)) {
        // the user's message is safe on the server (or this was a greeting/retry): offer to ask again
        setPhase('interrupted')
        if (failedKind === 'STREAM_INTERRUPTED' || failedKind === 'NETWORK') await reconcile()
        else setCanRetry(!NO_RETRY_KINDS.has(failedKind))
      } else {
        setPhase('idle')
      }
    },
    [loadOutline, reconcile],
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
        if (lastUser?.safetyUnavailable) setSafetyUnchecked(true)
        if (d.draft && d.draft.content.trim() && !inputRef.current.trim()) {
          saverRef.current?.markSaved(d.draft.content)
          setInput(d.draft.content)
          setDraftNotice(`${fmtTime(d.draft.updatedAt)}에 저장된 초안을 되살렸습니다.`)
        }
        const last = lastUsable(d.messages)
        if (last && (last.role === 'USER' || last.status === 'INTERRUPTED')) {
          // a reply never arrived last time (tab closed, server stopped): let the user ask again
          setPhase('interrupted')
          setCanRetry(true)
        }
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

  // a message was added (sent, answered or loaded)
  useEffect(() => {
    if (messages.length === 0) return
    if (!initialScrolled.current) {
      initialScrolled.current = true
      scrollToBottom(false)
      return
    }
    const last = messages[messages.length - 1]
    if (stickToBottom.current || last.role === 'USER') scrollToBottom(true) // your own message always comes into view
    else setNewBelow(true)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [messages.length])

  // the reply is streaming in
  useEffect(() => {
    if (streamingText === null) return
    if (stickToBottom.current) followFrame()
    else if (streamingText) setNewBelow(true)
  }, [streamingText, followFrame])

  // the visible area changed size (notices appeared, the window was resized): stay pinned to the bottom
  useEffect(() => {
    const el = chatRef.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(() => {
      if (stickToBottom.current) el.scrollTop = el.scrollHeight
    })
    ro.observe(el)
    return () => {
      ro.disconnect()
      if (scrollFrame.current !== null) window.cancelAnimationFrame(scrollFrame.current)
    }
  }, [detail])

  const pickFiles = async (list: FileList | null) => {
    setFileError(null)
    if (!list || list.length === 0) return
    try {
      const read = await Promise.all(Array.from(list).map(readFile))
      // A LINE export that is too long to attach whole opens the date range picker instead.
      const used = files.reduce((n, f) => n + f.chars, 0)
      const tooLong = read.find((f) => used + f.chars > MAX_TOTAL_CHARS && f.name.toLowerCase().endsWith('.txt'))
      const chat = tooLong ? parseLineChat(tooLong.content) : null
      const result = addFiles(files, chat ? read.filter((f) => f !== tooLong) : read)
      if (result.error) setFileError(result.error)
      else setFiles(result.files)
      if (chat && tooLong && !result.error) {
        if (result.files.length >= MAX_FILES) setFileError(`파일은 한 번에 ${MAX_FILES}개까지 첨부할 수 있습니다.`)
        else setLineChat({ name: tooLong.name, chat })
      }
    } catch {
      setFileError('파일을 읽지 못했습니다.')
    } finally {
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  const send = async () => {
    const text = input.trim()
    const sending = files
    if ((!text && sending.length === 0) || busy) return
    setInput('')
    setFiles([])
    setFileError(null)
    setDraftNotice(null)
    // the server replaces the draft with a tombstone at this revision, newer than any autosave so far
    const draftRev = saverRef.current?.nextRev()
    saverRef.current?.markSaved('')
    const tempId = -Date.now()
    const optimistic: MessageDto = {
      id: tempId,
      role: 'USER',
      content: text,
      status: 'COMPLETE',
      createdAt: new Date().toISOString(),
      attachments: sending.length
        ? sending.map((f, i) => ({ id: tempId - i - 1, name: f.name, charCount: f.chars, content: f.content }))
        : undefined,
    }
    setMessages((prev) => [...prev, optimistic])
    if (END_PHRASES.some((p) => text.includes(p))) setEndHint(true)
    await runTurn(
      `/api/sessions/${sessionId}/messages`,
      { content: text, draftRev, attachments: sending.map((f) => ({ name: f.name, content: f.content })) },
      { tempId, text, files: sending },
    )
  }

  const retry = async () => {
    if (busy) return
    await runTurn(`/api/sessions/${sessionId}/retry`, null)
  }

  const complete = async () => {
    if (!window.confirm('상담을 종료하고 기록을 정리할까요? 요약 생성에 잠시 시간이 걸립니다.')) return
    setCompleting(true)
    setError(null)
    try {
      const result = await api.completeSession(sessionId)
      saverRef.current?.markSaved(inputRef.current) // the server dropped the draft; do not re-save it on unmount
      navigate(`/history/${sessionId}`, { replace: true, state: { completion: result } })
    } catch (e) {
      setError(errorMessage(e) + ' 상담은 그대로 유지됩니다. 잠시 후 다시 종료를 시도하세요.')
      setCompleting(false)
    }
  }

  const doDelete = async () => {
    setDeleting(true)
    try {
      await api.deleteSession(sessionId)
      saverRef.current?.markSaved(inputRef.current)
      navigate('/', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
      setDeleting(false)
      setConfirmDelete(false)
    }
  }

  const onKey = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      void send()
    }
  }

  if (!detail && !error) return <div className="muted">불러오는 중…</div>

  const visible = messages.filter((m) => m.status !== 'FAILED')

  return (
    <div className="session-page">
      <div className="session-head">
        <div className="spread">
          <div className="session-title">
            <h1>상담 중</h1>
            <span className="muted small">
              {detail?.session.date} · 세션 #{sessionId}
              {detail?.checkIn && ' · 체크인 완료'}
            </span>
          </div>
          <div className="row">
            <Link className="btn sm" to="/">홈</Link>
            <button className="btn sm" onClick={() => setShowContext((v) => !v)} aria-expanded={showContext}>
              {showContext ? '참고 맥락 닫기' : '참고 맥락 보기'}
            </button>
            <button className="btn sm" onClick={() => setConfirmDelete(true)} disabled={busy || completing}>
              기록 없이 삭제
            </button>
            <button className="btn danger" onClick={complete} disabled={busy || completing}>
              {completing ? '기록을 정리하는 중…' : '상담 종료'}
            </button>
          </div>
        </div>
        {showContext && <ContextPanel outline={outline} onRefresh={loadOutline} />}
        <SafetyBanner level={risk} crisisFlag={detail?.session.crisisFlag ?? false} />
      </div>

      {confirmDelete && (
        <ConfirmDeleteModal
          title="진행 중인 상담을 삭제할까요?"
          details={['지금까지의 대화와 체크인이 요약 없이 사라집니다.', '작성 중이던 초안도 함께 삭제됩니다.', '이미 만들어진 백업과 복원 전 스냅샷에는 남아 있을 수 있습니다.']}
          busy={deleting}
          onCancel={() => setConfirmDelete(false)}
          onConfirm={doDelete}
        />
      )}

      <div className="chat-scroll" ref={chatRef} onScroll={onChatScroll}>
        <div className="chat">
          {visible.map((m) => (
            <ChatMessage key={m.id} role={m.role} content={m.content} createdAt={m.createdAt} status={m.status}
              safetyUnavailable={m.safetyUnavailable} attachments={m.attachments} />
          ))}
          {streamingText !== null && <ChatMessage role="ASSISTANT" content={streamingText || '…'} streaming />}
        </div>
        {newBelow && (
          <button className="btn sm new-below" onClick={() => scrollToBottom(true)}>
            새 내용 ↓
          </button>
        )}
      </div>

      <div className="session-notices">
        {safetyUnchecked && (
          <div className="alert warn" role="status">
            방금 메시지는 일시적인 오류로 <b>안전 검사를 하지 못했습니다.</b> 대화는 그대로 이어지지만, 위험이 없다고 확인된 것은 아닙니다.
            지금 위험하다고 느끼신다면 자살예방상담전화 109, 정신건강위기상담 1577-0199, 긴급 112/119에 연락하세요.
          </div>
        )}
        {omitted > 0 && (
          <div className="alert warn" role="status">
            상담이 길어져 앞부분 메시지 {omitted}개는 상담자에게 전달되지 않습니다. 중요한 내용이면 다시 한 번 언급해 주세요.
          </div>
        )}
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
        {canRetry && !busy && (
          <div className="alert warn">
            답변을 받지 못했습니다. 보낸 메시지는 서버에 저장되어 있으므로 다시 보낼 필요 없이 답변만 다시 요청할 수 있습니다.{' '}
            <button className="btn primary sm" onClick={retry}>답변 다시 요청</button>
          </div>
        )}
        {draftNotice && (
          <div className="alert info">
            {draftNotice}{' '}
            <button className="btn sm" onClick={() => setDraftNotice(null)}>확인</button>
          </div>
        )}
        {endHint && !busy && (
          <div className="alert info">
            오늘 상담을 마치려면 위쪽의 <b>상담 종료</b> 버튼을 누르세요. 요약과 Markdown 기록이 생성됩니다.
          </div>
        )}
      </div>

      {lineChat && (
        <LineRangeModal name={lineChat.name} chat={lineChat.chat}
          budget={MAX_TOTAL_CHARS - files.reduce((n, f) => n + f.chars, 0)}
          onCancel={() => setLineChat(null)}
          onAttach={(f) => {
            const result = addFiles(files, [f])
            if (result.error) setFileError(result.error)
            else setFiles(result.files)
            setLineChat(null)
          }} />
      )}
      <div className="composer">
        {(files.length > 0 || fileError) && (
          <div className="pending-files">
            {files.map((f, i) => (
              <span className="attachment-chip" key={`${f.name}-${i}`}>
                📎 {f.name} <span className="muted">({f.chars.toLocaleString()}자)</span>
                <button className="chip-remove" aria-label={`${f.name} 첨부 취소`}
                  onClick={() => setFiles((prev) => prev.filter((_, j) => j !== i))}>×</button>
              </span>
            ))}
            {files.length > 0 && (
              <div className="muted small">
                첨부한 파일 내용도 대화와 함께 OpenAI로 전송됩니다. 다른 사람의 메시지가 들어 있다면 그 내용도 함께 전송됩니다.
                상담자는 이번 답변에서만 원문을 보고, 이후에는 파일 이름만 참고합니다.
              </div>
            )}
            {fileError && <div className="small" style={{ color: 'var(--danger)' }}>{fileError}</div>}
          </div>
        )}
        <div className="composer-row">
          <input ref={fileInputRef} type="file" accept={ACCEPT} multiple hidden
            onChange={(e) => void pickFiles(e.target.files)} />
          <button className="btn attach" onClick={() => fileInputRef.current?.click()}
            disabled={busy || completing} title="텍스트(.txt)·Markdown(.md) 파일 첨부" aria-label="파일 첨부">
            📎
          </button>
          <textarea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={onKey}
            placeholder="이번 주 이야기하고 싶은 것을 편하게 적어 주세요. (Ctrl+Enter 전송)"
            disabled={busy || completing}
            rows={3}
          />
          <button className="btn primary send" onClick={send} disabled={busy || completing || (!input.trim() && files.length === 0)}>
            {busy ? (phase === 'sending' ? '전송 중…' : '생성 중…') : '보내기'}
          </button>
        </div>
        <div className="muted small composer-meta">
          {/* the first line shows the turn status while a reply is on its way, so the height never changes */}
          <div>
            {phase !== 'idle' ? (
              <span className={'turn-status ' + phase} role="status">
                <i />
                {PHASE_LABEL[phase]}
              </span>
            ) : (
              '· 상담자는 진단하지 않으며, 당신이 생각을 정리하도록 돕는 역할만 합니다.'
            )}
          </div>
          <div>
            {draftFailing ? <b style={{ color: 'var(--warn)' }}>· 초안 자동 저장 실패 · 다시 시도 중</b> : '· 작성 중인 글은 이 PC에 자동 저장됩니다.'}
          </div>
          <div>
            · Using ChatGPT plan · <a href={CHATGPT_USAGE_URL} target="_blank" rel="noreferrer noopener">Manage usage</a>
          </div>
        </div>
      </div>
    </div>
  )
}

function ContextPanel({ outline, onRefresh }: { outline: ContextOutline | null; onRefresh: () => void }) {
  if (!outline) return <div className="card soft muted small">참고 맥락을 불러오는 중…</div>
  const pct = outline.charsBudget > 0 ? Math.min(100, Math.round((outline.charsIncluded / outline.charsBudget) * 100)) : 0
  return (
    <div className="card soft context-panel">
      <div className="spread">
        <h2>이번 상담에서 상담자가 참고하는 것</h2>
        <button className="btn sm" onClick={onRefresh}>새로고침</button>
      </div>
      <p className="muted small">
        다음 답변을 만들 때 시스템 프롬프트와 함께 전달되는 자료입니다. 과거 상담의 대화 원문은 전달되지 않고 요약만 전달됩니다.
      </p>
      <div className="grid-2">
        <div>
          <h3>장기 기억 ({outline.memories.length}{outline.activeMemoryTotal > outline.memories.length ? ` / 확정 ${outline.activeMemoryTotal}` : ''})</h3>
          {outline.memories.length === 0 ? (
            <div className="muted small">확정된 기억이 아직 없습니다. <Link to="/memory">기억 화면</Link>에서 후보를 확정하면 여기에 포함됩니다.</div>
          ) : (
            <ul className="small">
              {outline.memories.map((m) => (
                <li key={m.id}>
                  <span className={'badge ' + (m.kind === 'USER_STATED' ? 'user' : 'ai')}>{m.kind === 'USER_STATED' ? '말함' : 'AI 관찰'}</span>{' '}
                  {m.content}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <h3>최근 상담 요약 ({outline.recentSummaries.length} / 설정 {outline.recentSummaryLimit})</h3>
          {outline.recentSummaries.length === 0 ? (
            <div className="muted small">{outline.recentSummaryLimit === 0 ? '설정에서 0으로 두어 포함하지 않습니다.' : '완료된 이전 상담이 없습니다.'}</div>
          ) : (
            <ul className="small">
              {outline.recentSummaries.map((s) => (
                <li key={s.sessionId}>
                  <Link to={`/history/${s.sessionId}`}>{s.date} · {s.title ?? '제목 없음'}</Link>
                  {!s.hasSummary && <span className="muted"> (요약 없음)</span>}
                </li>
              ))}
            </ul>
          )}
          {outline.importedArchives.length > 0 && (
            <>
              <h3>이관된 과거 기록 ({outline.importedArchives.length})</h3>
              <ul className="small">
                {outline.importedArchives.map((a) => (
                  <li key={a.sessionId}>
                    <Link to={`/history/${a.sessionId}`}>{a.date} · {a.title ?? '제목 없음'}</Link>
                    <span className="muted"> · {a.charsIncluded.toLocaleString()}자{a.truncated ? ' (길어서 일부만)' : ''}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      </div>
      <table className="plain" style={{ marginTop: 8 }}>
        <tbody>
          <tr><th>프로필 / 목표</th><td>{outline.profileIncluded ? '프로필 포함' : '프로필 없음'} · {outline.goalsIncluded ? '목표 포함' : '목표 없음'}</td></tr>
          <tr><th>이번 상담 체크인</th><td>{outline.checkInIncluded ? '포함' : '건너뜀'}</td></tr>
          <tr>
            <th>이번 상담 대화</th>
            <td>
              메시지 {outline.messagesIncluded}개 전달
              {outline.messagesOmitted > 0 && <b style={{ color: 'var(--warn)' }}> · 앞부분 {outline.messagesOmitted}개 생략</b>}
              <span className="muted"> · 분량 {outline.charsIncluded.toLocaleString()} / {outline.charsBudget.toLocaleString()}자 ({pct}%)</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  )
}
