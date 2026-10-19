import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { SessionListItemDto } from '../api/types'

export default function HistoryPage() {
  const PAGE = 50
  const [items, setItems] = useState<SessionListItemDto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(false)
  const [loadingMore, setLoadingMore] = useState(false)

  const loadPage = (offset: number) =>
    api.listSessions(PAGE, offset).then((page) => {
      setItems((prev) => (offset === 0 || !prev ? page : [...prev, ...page]))
      setHasMore(page.length === PAGE)
    })

  useEffect(() => {
    loadPage(0).catch((e) => setError(errorMessage(e)))
  }, [])

  const loadMore = async () => {
    if (!items) return
    setLoadingMore(true)
    try {
      await loadPage(items.length)
    } catch (e) {
      setError(errorMessage(e))
    } finally {
      setLoadingMore(false)
    }
  }

  if (error) return <div className="alert error">{error}</div>
  if (!items) return <div className="muted">불러오는 중…</div>

  return (
    <div>
      <div className="page-head">
        <h1>상담 기록</h1>
        <p>날짜별로 상담 요약과 원본 대화를 다시 볼 수 있습니다.</p>
      </div>
      <div className="card">
        {items.length === 0 && <div className="muted">아직 상담 기록이 없습니다.</div>}
        {items.map(({ session, topics, tags, oneLine, checkIn }) => (
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
            {tags.length > 0 && (
              <div style={{ marginTop: 4 }}>
                {tags.map((t) => (
                  <span className="tag" key={t}>{t}</span>
                ))}
              </div>
            )}
          </Link>
        ))}
        {hasMore && (
          <div style={{ textAlign: 'center', paddingTop: 12 }}>
            <button className="btn" onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? '불러오는 중…' : '이전 기록 더 보기'}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
