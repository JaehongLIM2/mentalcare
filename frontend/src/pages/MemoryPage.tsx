import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { MemoryDto, MemoryStatus, MemorySuggestion } from '../api/types'
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
            {memory.source === 'MANUAL' ? '직접 추가' : memory.kind === 'USER_STATED' ? '사용자가 말함' : 'AI 관찰 (해석)'}
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

  const [newContent, setNewContent] = useState('')
  const [newCategory, setNewCategory] = useState('other')
  const [adding, setAdding] = useState(false)
  const [addNotice, setAddNotice] = useState<string | null>(null)
  const [suggestions, setSuggestions] = useState<MemorySuggestion[] | null>(null)
  const [suggestionBusy, setSuggestionBusy] = useState<string | null>(null)
  const loadSuggestions = () => api.memorySuggestions(12).then(setSuggestions).catch(() => setSuggestions([]))
  useEffect(() => {
    void loadSuggestions()
  }, [])

  const useSuggestion = (s: MemorySuggestion) => {
    setNewContent(s.text)
    setNewCategory(s.category in CATEGORY_LABEL ? s.category : 'other')
    setAddNotice(null)
  }

  const addSuggestion = async (s: MemorySuggestion) => {
    setSuggestionBusy(s.key)
    setError(null)
    try {
      const created = await api.createMemory(s.text, s.category in CATEGORY_LABEL ? s.category : 'other')
      setAddNotice('추천 문장을 확정된 기억으로 추가했습니다.')
      if (filter === 'REVIEW' || filter === 'ACTIVE' || filter === 'ALL') setItems((prev) => (prev ? [created, ...prev] : [created]))
      await loadSuggestions()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSuggestionBusy(null)
    }
  }

  const dismissSuggestion = async (s: MemorySuggestion) => {
    setSuggestionBusy(s.key)
    try {
      await api.dismissMemorySuggestion(s.key)
      setSuggestions((prev) => (prev ? prev.filter((x) => x.key !== s.key) : prev))
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setSuggestionBusy(null)
    }
  }

  const addMemory = async () => {
    if (!newContent.trim()) return
    setAdding(true)
    setError(null)
    setAddNotice(null)
    try {
      const created = await api.createMemory(newContent.trim(), newCategory)
      setNewContent('')
      setAddNotice('확정된 기억으로 추가했습니다. 다음 답변부터 상담자가 참고합니다.')
      if (filter === 'REVIEW' || filter === 'ACTIVE' || filter === 'ALL') setItems((prev) => (prev ? [created, ...prev] : [created]))
      void loadSuggestions()
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setAdding(false)
    }
  }

  return (
    <div>
      <div className="page-head">
        <h1>기억</h1>
        <p>
          여러 상담에 걸쳐 유지되는 내용입니다. <b>확정</b>된 기억만 다음 상담의 맥락에 포함됩니다. 잘못된 내용은 수정하거나 보관하세요.
        </p>
      </div>

      <div className="card soft">
        <h2>기억 직접 추가</h2>
        <p className="muted small">
          상담자가 계속 알고 있었으면 하는 사실을 적어 두세요. 직접 추가한 기억은 바로 <b>확정</b>되어 다음 상담부터 참고됩니다.
          예: 가족·반려동물, 진행 중인 일, 지키고 싶은 가치관.
        </p>
        <textarea
          rows={2}
          value={newContent}
          maxLength={2000}
          onChange={(e) => setNewContent(e.target.value)}
          placeholder="예: 이직 여부를 올해 안에 결정하려고 한다. / 어머니가 무릎 수술 후 회복 중이다."
        />
        <div className="row" style={{ marginTop: 8 }}>
          <select value={newCategory} onChange={(e) => setNewCategory(e.target.value)} style={{ width: 'auto' }}>
            {Object.entries(CATEGORY_LABEL).map(([k, label]) => (
              <option key={k} value={k}>{label}</option>
            ))}
          </select>
          <button className="btn primary" onClick={addMemory} disabled={adding || !newContent.trim()}>
            {adding ? '추가하는 중…' : '기억 추가'}
          </button>
          {addNotice && <span className="muted small">{addNotice}</span>}
        </div>

        <h3 style={{ marginTop: 16 }}>최근 상담에서 추천</h3>
        <p className="muted small" style={{ marginTop: 0 }}>
          최근 상담 요약 중 직접 이야기한 사건과 생각에서, 아직 기억에 없는 문장을 골랐습니다. AI를 새로 호출하지 않고 이 PC의 기록에서만 가져옵니다.
        </p>
        {suggestions === null && <div className="muted small">불러오는 중…</div>}
        {suggestions !== null && suggestions.length === 0 && (
          <div className="muted small">추천할 문장이 없습니다. 상담을 마치면 새 추천이 생깁니다.</div>
        )}
        {suggestions !== null && suggestions.length > 0 && (
          <ul className="suggestions">
            {suggestions.map((s) => (
              <li key={s.key}>
                <div>{s.text}</div>
                <div className="spread" style={{ marginTop: 4 }}>
                  <span className="muted small">
                    <Link to={`/history/${s.sessionId}`}>{s.sessionDate}{s.sessionTitle ? ` · ${s.sessionTitle}` : ''}</Link> · {s.source}
                  </span>
                  <span className="row" style={{ gap: 6 }}>
                    <button className="btn sm primary" onClick={() => addSuggestion(s)} disabled={suggestionBusy !== null}>바로 추가</button>
                    <button className="btn sm" onClick={() => useSuggestion(s)} disabled={suggestionBusy !== null}>고쳐서 추가</button>
                    <button className="btn sm" onClick={() => dismissSuggestion(s)} disabled={suggestionBusy !== null}>추천 안 받기</button>
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}
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
