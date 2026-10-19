import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { TrendPoint } from '../api/types'
import LineChart from '../components/LineChart'
import { METRICS } from '../util/format'

export default function TrendsPage() {
  const [points, setPoints] = useState<TrendPoint[] | null>(null)
  const [limit, setLimit] = useState(12)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api.trends(limit).then(setPoints).catch((e) => setError(errorMessage(e)))
  }, [limit])

  if (error) return <div className="alert error">{error}</div>
  if (!points) return <div className="muted">불러오는 중…</div>

  return (
    <div>
      <div className="spread page-head">
        <div>
          <h1>추세</h1>
          <p>주간 체크인 점수의 변화입니다. 숫자는 참고용이며 진단이 아닙니다.</p>
        </div>
        <select value={limit} onChange={(e) => setLimit(Number(e.target.value))} style={{ width: 'auto' }}>
          <option value={8}>최근 8회</option>
          <option value={12}>최근 12회</option>
          <option value={26}>최근 26회</option>
          <option value={52}>최근 52회</option>
        </select>
      </div>

      <div className="card">
        <LineChart points={points} />
      </div>

      <div className="card">
        <h2>최근 값</h2>
        {points.length === 0 ? (
          <div className="muted small">체크인이 기록된 상담이 아직 없습니다.</div>
        ) : (
          <table className="plain">
            <thead>
              <tr>
                <th>지표</th>
                <th>변화 (오래된 → 최근)</th>
              </tr>
            </thead>
            <tbody>
              {METRICS.map((m) => (
                <tr key={m.key}>
                  <td>{m.label}</td>
                  <td className="num" style={{ textAlign: 'left' }}>
                    {points.map((p) => (p[m.key] ?? '–')).join(' → ')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <div className="muted small" style={{ marginTop: 10 }}>
          각 점은 한 번의 상담에 해당합니다. <Link to="/history">상담 기록</Link>에서 그날의 대화를 확인할 수 있습니다.
        </div>
      </div>
    </div>
  )
}
