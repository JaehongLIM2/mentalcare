import { NavLink, Outlet } from 'react-router-dom'
import ServerStatus from './ServerStatus'

const links = [
  { to: '/', label: '홈', end: true },
  { to: '/new', label: '새 상담' },
  { to: '/history', label: '상담 기록' },
  { to: '/trends', label: '추세' },
  { to: '/memory', label: '기억' },
  { to: '/settings', label: '설정' },
]

export default function Layout() {
  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          mentalcare
          <small>주간 자기성찰 상담실</small>
        </div>
        {links.map((l) => (
          <NavLink key={l.to} to={l.to} end={l.end} className={({ isActive }) => 'nav-link' + (isActive ? ' active' : '')}>
            {l.label}
          </NavLink>
        ))}
        <div className="sidebar-foot">
          <ServerStatus />
          이 도구는 진료나 심리치료를 대체하지 않습니다. 긴급한 상황에서는 주변 사람이나 전문 기관에 연락하세요.
        </div>
      </aside>
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}
