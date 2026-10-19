import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { api } from '../api/client'
import QuitAppButton from './QuitAppButton'
import ServerStatus, { SHUTDOWN_EVENT } from './ServerStatus'

export default function Layout() {
  const location = useLocation()
  const [activeId, setActiveId] = useState<number | null>(null)
  const [stopped, setStopped] = useState(false)

  // Starting, ending or deleting a session always changes the route, so re-check on every navigation.
  useEffect(() => {
    let cancelled = false
    api.activeSession()
      .then((s) => { if (!cancelled) setActiveId(s?.id ?? null) })
      .catch(() => {})
    return () => { cancelled = true }
  }, [location.pathname])

  useEffect(() => {
    const onShutdown = () => setStopped(true)
    window.addEventListener(SHUTDOWN_EVENT, onShutdown)
    return () => window.removeEventListener(SHUTDOWN_EVENT, onShutdown)
  }, [])

  const links = [
    { to: '/', label: '홈', end: true },
    activeId
      ? { to: `/session/${activeId}`, label: '상담 이어하기', end: false }
      : { to: '/new', label: '새 상담', end: false },
    { to: '/history', label: '상담 기록', end: false },
    { to: '/trends', label: '추세', end: false },
    { to: '/memory', label: '기억', end: false },
    { to: '/settings', label: '설정', end: false },
  ]

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          mentalcare
          <small>주간 자기성찰 상담실</small>
        </div>
        {links.map((l) => (
          <NavLink key={l.label} to={l.to} end={l.end} className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>
            {l.label}
          </NavLink>
        ))}
        <div className="sidebar-foot">
          <ServerStatus />
          이 도구는 진료나 심리치료를 대체하지 않습니다. 긴급한 상황에서는 주변 사람이나 전문 기관에 연락하세요.
          {!stopped && <QuitAppButton className="btn sm quit-app" />}
        </div>
      </aside>
      <main className="main">
        {stopped ? (
          <div className="card overlay-note">
            <h1>앱이 종료되었습니다</h1>
            <p className="muted">
              서버가 정상적으로 멈췄고 상담 데이터는 모두 저장되어 있습니다. 이 브라우저 탭은 닫아도 됩니다.
            </p>
            <p className="muted small">
              다시 사용하려면 <b>MentalCare 시작.cmd</b>(Windows) 또는 <code>docker compose up</code>으로 실행하세요.
            </p>
          </div>
        ) : (
          <Outlet />
        )}
      </main>
    </div>
  )
}
