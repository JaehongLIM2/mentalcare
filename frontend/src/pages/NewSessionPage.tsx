import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api, ApiError, errorMessage, type CheckInInput } from '../api/client'
import CheckInForm from '../components/CheckInForm'

export default function NewSessionPage() {
  const navigate = useNavigate()
  const [checkIn, setCheckIn] = useState<CheckInInput>({})
  const [activeId, setActiveId] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.activeSession().then((s) => setActiveId(s?.id ?? null)).catch(() => {})
  }, [])

  const start = async () => {
    setBusy(true)
    setError(null)
    try {
      const hasAny = Object.values(checkIn).some((v) => v !== null && v !== undefined && v !== '')
      const session = await api.createSession(hasAny ? checkIn : null)
      navigate(`/session/${session.id}`, { replace: true })
    } catch (e) {
      if (e instanceof ApiError && e.status === 409 && e.body.relatedId) {
        setActiveId(e.body.relatedId)
      }
      setError(errorMessage(e))
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="page-head">
        <h1>새 상담</h1>
        <p>시작하기 전에 이번 주 상태를 간단히 기록합니다. 모든 항목은 선택이며 건너뛸 수 있습니다.</p>
      </div>

      {activeId && (
        <div className="alert info">
          진행 중인 상담이 있습니다. <Link to={`/session/${activeId}`}>이어서 진행</Link>하거나 그 상담을 먼저 종료하세요.
        </div>
      )}
      {error && <div className="alert error">{error}</div>}

      <div className="card">
        <h2>Weekly Check-in</h2>
        <CheckInForm value={checkIn} onChange={setCheckIn} />
      </div>

      <div className="row">
        <button className="btn primary" onClick={start} disabled={busy || activeId !== null}>
          {busy ? '시작하는 중…' : '상담 시작'}
        </button>
        <button className="btn" onClick={() => setCheckIn({})} disabled={busy}>
          모두 건너뛰기
        </button>
        <span className="muted small">체크인 점수는 참고 자료일 뿐, 진단에 쓰이지 않습니다.</span>
      </div>
    </div>
  )
}
