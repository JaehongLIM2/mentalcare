import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { SystemStatus } from '../api/types'
import { fmtTime } from '../util/format'

/** Name of the DOM event the settings page fires right after it asked the server to quit. */
export const SHUTDOWN_EVENT = 'mentalcare:shutdown'

const POLL_MS = 30_000

/**
 * Sidebar indicator: is the local server (which keeps running after the browser tab is closed) still up?
 * Polls the cheap status endpoint; a failed poll means the server is gone or unreachable.
 */
export default function ServerStatus() {
  const [status, setStatus] = useState<SystemStatus | null>(null)
  const [down, setDown] = useState(false)
  const [stopped, setStopped] = useState(false)

  useEffect(() => {
    let cancelled = false
    const poll = () =>
      api.system
        .status()
        .then((s) => {
          if (cancelled) return
          setStatus(s)
          setDown(false)
        })
        .catch(() => {
          if (!cancelled) setDown(true)
        })
    void poll()
    const timer = window.setInterval(poll, POLL_MS)
    const onVisible = () => {
      if (document.visibilityState === 'visible') void poll()
    }
    const onShutdown = () => {
      setStopped(true)
      setDown(true)
    }
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener(SHUTDOWN_EVENT, onShutdown)
    return () => {
      cancelled = true
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener(SHUTDOWN_EVENT, onShutdown)
    }
  }, [])

  if (down) {
    return (
      <div className="server-status down" role="status">
        <i />
        {stopped ? '앱이 종료되었습니다' : '서버에 연결할 수 없습니다'}
        <small>{stopped ? '이 탭은 닫아도 됩니다.' : '"MentalCare 시작"으로 다시 실행하세요.'}</small>
      </div>
    )
  }
  if (!status) return null
  return (
    <Link className="server-status up" to="/settings#app-status" title="앱 실행 상태 보기">
      <i />
      서버 실행 중
      {status.busySessions > 0 && ' · 응답 생성 중'}
      <small>시작 {fmtTime(status.startedAt)} · 포트 {status.port}</small>
    </Link>
  )
}
