import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { HomeView } from '../api/types'
import ChatGptConnect from '../components/ChatGptConnect'
import LineChart from '../components/LineChart'
import WelcomeModal from '../components/WelcomeModal'
import { METRICS } from '../util/format'

export default function HomePage() {
  const [view, setView] = useState<HomeView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [welcome, setWelcome] = useState(false)

  const load = () => api.home().then(setView).catch((e) => setError(errorMessage(e)))

  useEffect(() => {
    void load()
    const params = new URLSearchParams(window.location.search)
    if (params.get('chatgpt') === 'connected') {
      api.settings().then((s) => {
        if (!s.welcomeShown) setWelcome(true)
      }).catch(() => {})
      window.history.replaceState(null, '', window.location.pathname)
    }
  }, [])

  const closeWelcome = () => {
    setWelcome(false)
    api.updateSettings({ welcomeShown: true }).catch(() => {})
  }

  if (error) return <div className="alert error">{error}</div>
  if (!view) return <div className="muted">불러오는 중…</div>

  const last = view.recentCheckIns.length ? view.recentCheckIns[view.recentCheckIns.length - 1] : null

  return (
    <div>
      {welcome && <WelcomeModal onClose={closeWelcome} />}
      <div className="page-head">
        <h1>이번 주, 어떻게 지내셨나요?</h1>
        <p>매주 한 번, 한 주를 돌아보고 생각을 정리하는 공간입니다.</p>
      </div>

      {!view.aiReady && (
        <div className="card">
          <h2>{view.authMode === 'CHATGPT' ? 'ChatGPT 계정 연결이 필요합니다' : 'AI를 사용할 수 없습니다'}</h2>
          {view.aiUnavailableReason && <p className="muted">{view.aiUnavailableReason}</p>}
          <ChatGptConnect compact onChange={() => void load()} />
        </div>
      )}

      <div className="card">
        <div className="spread">
          <div>
            {view.activeSessionId ? (
              <>
                <h2>진행 중인 상담이 있습니다</h2>
                <p className="muted">브라우저를 닫았더라도 이어서 진행할 수 있습니다.</p>
              </>
            ) : view.dueNow ? (
              <>
                <h2>{view.lastSessionDate ? '이번 주 상담을 시작할 때입니다' : '첫 상담을 시작해 보세요'}</h2>
                <p className="muted">
                  {view.lastSessionDate
                    ? `마지막 상담 ${view.lastSessionDate} (${view.daysSinceLast}일 전)`
                    : '체크인 후 상담자가 먼저 인사를 건넵니다.'}
                </p>
              </>
            ) : (
              <>
                <h2>다음 상담 권장일: {view.recommendedNextDate}</h2>
                <p className="muted">
                  마지막 상담 {view.lastSessionDate} ({view.daysSinceLast}일 전). 물론 지금 시작해도 괜찮습니다.
                </p>
              </>
            )}
          </div>
          {view.activeSessionId ? (
            <Link className="btn primary" to={`/session/${view.activeSessionId}`}>
              상담 이어하기
            </Link>
          ) : view.aiReady ? (
            <Link className="btn primary" to="/new">
              새 상담 시작
            </Link>
          ) : (
            <span className="btn" aria-disabled="true" style={{ opacity: 0.5 }}>
              새 상담 시작
            </span>
          )}
        </div>
        {view.usesChatGptPlan && view.aiReady && (
          <div className="muted small" style={{ marginTop: 8 }}>
            Using ChatGPT plan ·{' '}
            <a href="https://chatgpt.com/settings/usage" target="_blank" rel="noreferrer noopener">
              Manage usage
            </a>
          </div>
        )}
      </div>

      <div className="grid-2">
        <div className="card">
          <h2>최근 체크인</h2>
          {last ? (
            <>
              <div className="muted small" style={{ marginBottom: 8 }}>{last.date}</div>
              <div className="row" style={{ gap: 20 }}>
                {METRICS.map((m) => (
                  <div className="stat" key={m.key}>
                    <b>{last[m.key] ?? '–'}</b>
                    <span>{m.label}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <div className="muted small">아직 체크인 기록이 없습니다.</div>
          )}
          {view.recentCheckIns.length > 1 && (
            <div style={{ marginTop: 14 }}>
              <LineChart points={view.recentCheckIns} compact height={150} initial={['mood', 'stress']} />
              <div className="muted small">기분(초록) · 스트레스(빨강), 최근 {view.recentCheckIns.length}회 · <Link to="/trends">전체 추세</Link></div>
            </div>
          )}
        </div>

        <div className="card">
          <h2>지난 상담에서 이어지는 것</h2>
          {view.lastSessionId ? (
            <>
              <div className="muted small" style={{ marginBottom: 8 }}>
                <Link to={`/history/${view.lastSessionId}`}>
                  {view.lastSessionDate} · {view.lastSessionTitle ?? '제목 없음'}
                </Link>
              </div>
              {view.lastActionItems.length > 0 && (
                <>
                  <h3>이번 주에 해보기로 한 것</h3>
                  <ul>{view.lastActionItems.map((a, i) => <li key={i}>{a}</li>)}</ul>
                </>
              )}
              {view.lastNextSessionTopics.length > 0 && (
                <>
                  <h3>다음 상담에서 확인할 것</h3>
                  <ul>{view.lastNextSessionTopics.map((a, i) => <li key={i}>{a}</li>)}</ul>
                </>
              )}
              {view.lastActionItems.length === 0 && view.lastNextSessionTopics.length === 0 && (
                <div className="muted small">지난 상담에서 정리된 후속 항목이 없습니다.</div>
              )}
            </>
          ) : (
            <div className="muted small">완료된 상담이 아직 없습니다.</div>
          )}
        </div>
      </div>

      <div className="grid-2">
        <div className="card soft">
          <h2>현재 상담 목표</h2>
          {view.goals ? (
            <div style={{ whiteSpace: 'pre-wrap' }}>{view.goals}</div>
          ) : (
            <div className="muted small">
              <Link to="/settings">설정</Link>에서 유지하고 싶은 목표를 적어두면 상담자가 참고합니다.
            </div>
          )}
        </div>
        <div className="card soft">
          <h2>기록</h2>
          <div className="row" style={{ gap: 24 }}>
            <div className="stat"><b>{view.totalSessions}</b><span>완료된 상담</span></div>
            <div className="stat"><b>{view.activeMemories}</b><span>확정된 기억</span></div>
            <div className="stat"><b>{view.candidateMemories}</b><span>기억 후보</span></div>
          </div>
          <div className="muted small" style={{ marginTop: 10 }}>
            <Link to="/history">상담 기록</Link> · <Link to="/memory">기억 검토</Link>
          </div>
        </div>
      </div>
    </div>
  )
}
