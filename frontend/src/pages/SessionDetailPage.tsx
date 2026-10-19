import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { CompletionDto, SessionDetailDto } from '../api/types'
import ChatMessage from '../components/ChatMessage'
import ConfirmDeleteModal from '../components/ConfirmDeleteModal'
import SummaryView from '../components/SummaryView'
import { METRICS } from '../util/format'

export default function SessionDetailPage() {
  const { id } = useParams()
  const sessionId = Number(id)
  const location = useLocation()
  const completion = (location.state as { completion?: CompletionDto } | null)?.completion
  const [detail, setDetail] = useState<SessionDetailDto | null>(null)
  const [tab, setTab] = useState<'summary' | 'transcript'>('summary')
  const [error, setError] = useState<string | null>(null)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const navigate = useNavigate()

  useEffect(() => {
    api.session(sessionId).then(setDetail).catch((e) => setError(errorMessage(e)))
  }, [sessionId])

  const doDelete = async () => {
    setDeleting(true)
    try {
      await api.deleteSession(sessionId)
      navigate('/history', { replace: true })
    } catch (e) {
      setError(errorMessage(e))
      setDeleting(false)
      setConfirmDelete(false)
    }
  }

  if (error) return <div className="alert error">{error}</div>
  if (!detail) return <div className="muted">불러오는 중…</div>

  const { session, checkIn, messages, summary, tags, importedMarkdown } = detail
  const imported = session.kind === 'IMPORTED'

  return (
    <div>
      <div className="spread page-head">
        <div>
          <h1>{session.title ?? session.date}</h1>
          <p>
            {session.date} · 세션 #{session.id} · {imported ? '다른 도구에서 이관된 기록' : session.status === 'ACTIVE' ? '진행 중' : '완료'}
            {session.promptVersion && ` · 프롬프트 ${session.promptVersion}`}
          </p>
        </div>
        <div className="row">
          <Link className="btn sm" to="/history">목록</Link>
          {session.status === 'ACTIVE' && (
            <Link className="btn primary sm" to={`/session/${session.id}`}>이어서 진행</Link>
          )}
          <button className="btn danger sm" onClick={() => setConfirmDelete(true)}>이 상담 삭제</button>
        </div>
      </div>

      {confirmDelete && (
        <ConfirmDeleteModal
          title={`${session.date} 상담을 삭제할까요?`}
          details={[
            '이 상담의 대화 원문, 체크인, AI 요약, 태그, 안전 기록',
            '이 상담에서만 관찰된 기억 후보 (다른 상담에서도 관찰된 기억은 남습니다)',
            summary?.markdownPath ? 'Markdown 기록 파일' : '',
          ].filter(Boolean)}
          busy={deleting}
          onCancel={() => setConfirmDelete(false)}
          onConfirm={doDelete}
        />
      )}

      {completion && (
        <div className="alert info">
          상담이 저장되었습니다. 기억 후보 {completion.memoriesCreated}개 생성, {completion.memoriesMerged}개 병합,{' '}
          {completion.memoriesActivated}개 확정.
          {completion.warning && <div style={{ marginTop: 4 }}>{completion.warning}</div>}
        </div>
      )}

      {checkIn && (
        <div className="card soft">
          <div className="row" style={{ gap: 20 }}>
            {METRICS.map((m) => (
              <div className="stat" key={m.key}>
                <b>{checkIn[m.key] ?? '–'}</b>
                <span>{m.label}</span>
              </div>
            ))}
            {checkIn.note && <div className="muted small" style={{ flexBasis: '100%' }}>메모: {checkIn.note}</div>}
          </div>
        </div>
      )}

      <div className="tabs">
        <button className={tab === 'summary' ? 'on' : ''} onClick={() => setTab('summary')}>AI 요약</button>
        <button className={tab === 'transcript' ? 'on' : ''} onClick={() => setTab('transcript')}>
          {imported ? '이관 원본 (Markdown)' : `원본 대화 (${messages.length})`}
        </button>
      </div>

      {tab === 'summary' ? (
        <div className="card">
          {summary ? (
            <SummaryView summary={summary} tags={tags} />
          ) : (
            <div className="muted">
              {session.status === 'ACTIVE' ? '상담이 끝나면 요약이 생성됩니다.' : '이 상담에는 요약이 없습니다.'}
            </div>
          )}
        </div>
      ) : imported ? (
        <div className="card">
          <div className="muted small" style={{ marginBottom: 12 }}>
            다른 상담 도구에서 작성된 이관 기록의 원문입니다. 서식 없이 텍스트 그대로 표시합니다.
          </div>
          <pre style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontFamily: 'inherit', margin: 0 }}>{importedMarkdown ?? '(원문 없음)'}</pre>
        </div>
      ) : (
        <div className="card">
          <div className="muted small" style={{ marginBottom: 12 }}>
            아래는 실제 대화 원문입니다. AI 요약과 혼동되지 않도록 분리해 보여줍니다.
          </div>
          <div className="chat">
            {messages.map((m) => (
              <ChatMessage key={m.id} role={m.role} content={m.content} createdAt={m.createdAt} status={m.status} />
            ))}
            {messages.length === 0 && <div className="muted">대화가 없습니다.</div>}
          </div>
        </div>
      )}
    </div>
  )
}
