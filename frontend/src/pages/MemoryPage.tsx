import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { MemoryDto, MemoryStatus } from '../api/types'
import { CATEGORY_LABEL } from '../util/format'

type Filter = 'REVIEW' | MemoryStatus | 'ALL'

const STATUS_LABEL: Record<MemoryStatus, string> = { CANDIDATE: '후보', ACTIVE: '확정', ARCHIVED: '보관' }

function MemoryCard({ memory, onSaved }: { memory: MemoryDto; onSaved: (m: MemoryDto) => void }) {
  const [editing, setEditing] = useState(false)
  const [content, setContent] = useState(memory.content)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const patch = async (p: { content?: string; status?: MemoryStatus }) => {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.updateMemory(memory.id, p)
      onSaved(updated)
      setEditing(false)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setBusy(false)
    }
  }

  const first = memory.observations[0]
  const last = memory.observations[memory.observations.length - 1]

  return (
    <div className="card">
      <div className="spread" style={{ marginBottom: 8 }}>
        <div className="row" style={{ gap: 6 }}>
          <span className={'badge ' + memory.status.toLowerCase()}>{STATUS_LABEL[memory.status]}</span>
          <span className={'badge ' + (memory.kind === 'USER_STATED' ? 'user' : 'ai')}>
            {memory.kind === 'USER_STATED' ? '사용자가 말함' : 'AI 관찰 (해석)'}
          </span>
          <span className="muted small">{CATEGORY_LABEL[memory.category] ?? memory.category}</span>
        </div>
        <span className="muted small">관찰 {memory.observationCount}회</span>
      </div>

      {editing ? (
        <textarea value={content} onChange={(e) => setContent(e.target.value)} rows={3} />
      ) : (
        <div style={{ fontSize: '1.02rem' }}>{memory.content}</div>
      )}

      <div className="muted small" style={{ marginTop: 8 }}>
        처음 {first?.sessionDate ?? '-'} · 최근 {last?.sessionDate ?? '-'} · 관련 상담:{' '}
        {memory.observations.length === 0
          ? '-'
          : memory.observations.map((o, i) => (
              <span key={o.sessionId}>
                {i > 0 && ', '}
                <Link to={`/history/${o.sessionId}`}>{o.sessionDate}</Link>
              </span>
            ))}
      </div>
      {error && <div className="alert error" style={{ marginTop: 8 }}>{error}</div>}

      <div className="row" style={{ marginTop: 12 }}>
        {editing ? (
          <>
            <button className="btn primary sm" disabled={busy || !content.trim()} onClick={() => patch({ content })}>저장</button>
            <button className="btn sm" disabled={busy} onClick={() => { setEditing(false); setContent(memory.content) }}>취소</button>
          </>
        ) : (
          <button className="btn sm" disabled={busy} onClick={() => setEditing(true)}>내용 수정</button>
        )}
        {memory.status !== 'ACTIVE' && (
          <button className="btn sm" disabled={busy} onClick={() => patch({ status: 'ACTIVE' })}>확정 (상담에 반영)</button>
        )}
        {memory.status !== 'CANDIDATE' && (
          <button className="btn sm" disabled={busy} onClick={() => patch({ status: 'CANDIDATE' })}>후보로</button>
        )}
        {memory.status !== 'ARCHIVED' && (
          <button className="btn sm" disabled={busy} onClick={() => patch({ status: 'ARCHIVED' })}>보관 (제외)</button>
        )}
      </div>
    </div>
  )
}

export default function MemoryPage() {
  const [filter, setFilter] = useState<Filter>('REVIEW')
  const [items, setItems] = useState<MemoryDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = (f: Filter) => {
    const arg = f === 'REVIEW' ? undefined : f === 'ALL' ? 'ALL' : [f]
    api.memories(arg).then(setItems).catch((e) => setError(errorMessage(e)))
  }
  useEffect(() => load(filter), [filter])

  const onSaved = (m: MemoryDto) => setItems((prev) => (prev ? prev.map((x) => (x.id === m.id ? m : x)) : prev))

  return (
    <div>
      <div className="page-head">
        <h1>기억</h1>
        <p>
          여러 상담에 걸쳐 유지되는 내용입니다. <b>확정</b>된 기억만 다음 상담의 맥락에 포함됩니다. 잘못된 내용은 수정하거나 보관하세요.
        </p>
      </div>

      <div className="tabs">
        {(
          [
            ['REVIEW', '후보 + 확정'],
            ['CANDIDATE', '후보'],
            ['ACTIVE', '확정'],
            ['ARCHIVED', '보관'],
            ['ALL', '전체'],
          ] as [Filter, string][]
        ).map(([f, label]) => (
          <button key={f} className={filter === f ? 'on' : ''} onClick={() => setFilter(f)}>{label}</button>
        ))}
      </div>

      {error && <div className="alert error">{error}</div>}
      {!items && !error && <div className="muted">불러오는 중…</div>}
      {items && items.length === 0 && <div className="muted">해당하는 기억이 없습니다. 상담을 종료하면 후보가 생성됩니다.</div>}
      {items?.map((m) => <MemoryCard key={m.id} memory={m} onSaved={onSaved} />)}
    </div>
  )
}
