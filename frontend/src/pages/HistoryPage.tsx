import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { SessionFilter, SessionListItemDto, TagCount } from '../api/types'

const PAGE = 50
const SEARCH_DEBOUNCE_MS = 350

export default function HistoryPage() {
  const [items, setItems] = useState<SessionListItemDto[] | null>(null)
  const [total, setTotal] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [tags, setTags] = useState<TagCount[]>([])
  const [q, setQ] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [tag, setTag] = useState('')
  const [applied, setApplied] = useState<SessionFilter>({})

  const filtering = !!(applied.q || applied.from || applied.to || applied.tag)

  const loadPage = (filter: SessionFilter, offset: number) =>
    api.searchSessions(filter, PAGE, offset).then((page) => {
      setItems((prev) => (offset === 0 || !prev ? page.items : [...prev, ...page.items]))
      setTotal(page.total)
    })

  // keyword is debounced; the other filters apply immediately
  useEffect(() => {
    const t = window.setTimeout(() => setApplied({ q: q.trim() || undefined, from: from || undefined, to: to || undefined, tag: tag || undefined }), SEARCH_DEBOUNCE_MS)
    return () => window.clearTimeout(t)
  }, [q, from, to, tag])

  useEffect(() => {
    setError(null)
    loadPage(applied, 0).catch((e) => setError(errorMessage(e)))
  }, [applied])

  useEffect(() => {
    api.tags().then(setTags).catch(() => setTags([]))
  }, [])

  const loadMore = async () => {
    if (!items) return
    setLoadingMore(true)
    try {
      await loadPage(applied, items.length)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLoadingMore(false)
    }
  }

  const reset = () => {
    setQ('')
    setFrom('')
    setTo('')
    setTag('')
  }

  const hasMore = items !== null && items.length < total

  return (
    <div>
      <div className="page-head">
        <h1>상담 기록</h1>
        <p>날짜별로 상담 요약과 원본 대화를 다시 볼 수 있습니다. 검색은 이 PC 안에서만 이루어집니다.</p>
      </div>

      <div className="card filters">
        <div className="filters-row">
          <label className="field" style={{ flex: '2 1 240px' }}>
            <span>검색어 (대화 원문 · 요약 · 제목 · 체크인 메모 · 이관 기록)</span>
            <input type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="예: 면담, 잠, 동생" maxLength={200} />
          </label>
          <label className="field" style={{ flex: '1 1 150px' }}>
            <span>시작일</span>
            <input type="date" value={from} max={to || undefined} onChange={(e) => setFrom(e.target.value)} />
          </label>
          <label className="field" style={{ flex: '1 1 150px' }}>
            <span>종료일</span>
            <input type="date" value={to} min={from || undefined} onChange={(e) => setTo(e.target.value)} />
          </label>
          <label className="field" style={{ flex: '1 1 150px' }}>
            <span>태그</span>
            <select value={tag} onChange={(e) => setTag(e.target.value)}>
              <option value="">전체</option>
              {tags.map((t) => (
                <option key={t.name} value={t.name}>{t.name} ({t.count})</option>
              ))}
            </select>
          </label>
        </div>
        <div className="spread">
          <span className="muted small" role="status">
            {items === null ? '불러오는 중…' : filtering ? `${total}건 일치` : `전체 ${total}건`}
          </span>
          {filtering && <button className="btn sm" onClick={reset}>필터 초기화</button>}
        </div>
      </div>

      {error && <div className="alert error">{error}</div>}
      {items === null && !error && <div className="muted">불러오는 중…</div>}
      {items !== null && (
        <div className="card">
          {items.length === 0 && (
            <div className="muted">{filtering ? '조건에 맞는 상담이 없습니다.' : '아직 상담 기록이 없습니다.'}</div>
          )}
          {items.map(({ session, topics, tags: itemTags, oneLine, checkIn }) => (
            <Link
              key={session.id}
              className="list-item"
              to={session.status === 'ACTIVE' ? `/session/${session.id}` : `/history/${session.id}`}
            >
              <div className="spread">
                <div>
                  <b>{session.date}</b>{' '}
                  {session.status === 'ACTIVE' ? (
                    <span className="badge candidate">진행 중</span>
                  ) : (
                    <span>{session.title ?? '제목 없음'}</span>
                  )}
                  {session.crisisFlag && <span className="badge ai" style={{ marginLeft: 6 }}>안전 모드 기록</span>}
                  {session.kind === 'IMPORTED' && <span className="badge candidate" style={{ marginLeft: 6 }}>이관</span>}
                </div>
                <div className="muted small">
                  {checkIn &&
                    [
                      checkIn.mood != null ? `기분 ${checkIn.mood}` : null,
                      checkIn.stress != null ? `스트레스 ${checkIn.stress}` : null,
                    ]
                      .filter(Boolean)
                      .join(' · ')}
                </div>
              </div>
              {topics.length > 0 && <div className="small">{topics.join(' · ')}</div>}
              {oneLine && <div className="muted small">{oneLine}</div>}
              {itemTags.length > 0 && (
                <div style={{ marginTop: 4 }}>
                  {itemTags.map((t) => (
                    <span className={'tag' + (t === applied.tag ? ' on' : '')} key={t}>{t}</span>
                  ))}
                </div>
              )}
            </Link>
          ))}
          {hasMore && (
            <div style={{ textAlign: 'center', paddingTop: 12 }}>
              <button className="btn" onClick={loadMore} disabled={loadingMore}>
                {loadingMore ? '불러오는 중…' : `이전 기록 더 보기 (${items.length} / ${total})`}
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
