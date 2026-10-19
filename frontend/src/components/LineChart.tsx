import { useState } from 'react'
import type { TrendPoint } from '../api/types'
import { METRICS, type MetricKey } from '../util/format'

interface Props {
  points: TrendPoint[]
  height?: number
  compact?: boolean
  initial?: MetricKey[]
}

const COLORS: Record<MetricKey, string> = {
  mood: 'var(--c-mood)',
  stress: 'var(--c-stress)',
  anxiety: 'var(--c-anxiety)',
  energy: 'var(--c-energy)',
  sleep: 'var(--c-sleep)',
}

/** Dependency-free SVG line chart of the weekly check-in scores (1..10). */
export default function LineChart({ points, height = 260, compact = false, initial }: Props) {
  const [enabled, setEnabled] = useState<Set<MetricKey>>(new Set(initial ?? METRICS.map((m) => m.key)))

  if (points.length === 0) {
    return <div className="muted small">아직 체크인 데이터가 없습니다.</div>
  }

  const width = Math.max(compact ? 320 : 640, points.length * (compact ? 48 : 70))
  const padL = 32, padR = 16, padT = 12, padB = compact ? 22 : 36
  const w = width - padL - padR
  const h = height - padT - padB
  const x = (i: number) => padL + (points.length === 1 ? w / 2 : (i / (points.length - 1)) * w)
  const y = (v: number) => padT + h - ((v - 1) / 9) * h

  const toggle = (k: MetricKey) =>
    setEnabled((prev) => {
      const next = new Set(prev)
      if (next.has(k)) next.delete(k)
      else next.add(k)
      return next
    })

  return (
    <div>
      {!compact && (
        <div className="legend">
          {METRICS.map((m) => (
            <button key={m.key} type="button" className={enabled.has(m.key) ? '' : 'off'} onClick={() => toggle(m.key)}>
              <i style={{ background: COLORS[m.key] }} /> {m.label}
            </button>
          ))}
        </div>
      )}
      <div className="chart-wrap">
        <svg width={width} height={height} role="img" aria-label="주간 체크인 추세">
          {[1, 4, 7, 10].map((v) => (
            <g key={v}>
              <line x1={padL} x2={width - padR} y1={y(v)} y2={y(v)} stroke="var(--border)" strokeDasharray="3 4" />
              <text x={padL - 8} y={y(v) + 4} fontSize="10" textAnchor="end" fill="var(--muted)">
                {v}
              </text>
            </g>
          ))}
          {points.map((p, i) => (
            <text key={p.sessionId} x={x(i)} y={height - padB + 16} fontSize="10" textAnchor="middle" fill="var(--muted)">
              {compact ? p.date.slice(5) : p.date}
            </text>
          ))}
          {METRICS.filter((m) => enabled.has(m.key)).map((m) => {
            const segs: string[] = []
            let current: string[] = []
            points.forEach((p, i) => {
              const v = p[m.key]
              if (v === null || v === undefined) {
                if (current.length) segs.push(current.join(' '))
                current = []
              } else {
                current.push(`${x(i)},${y(v)}`)
              }
            })
            if (current.length) segs.push(current.join(' '))
            return (
              <g key={m.key}>
                {segs.map((s, i) => (
                  <polyline key={i} points={s} fill="none" stroke={COLORS[m.key]} strokeWidth="2" strokeLinejoin="round" />
                ))}
                {points.map((p, i) => {
                  const v = p[m.key]
                  if (v === null || v === undefined) return null
                  return (
                    <circle key={i} cx={x(i)} cy={y(v)} r={3.5} fill={COLORS[m.key]}>
                      <title>{`${p.date} ${m.label} ${v}`}</title>
                    </circle>
                  )
                })}
              </g>
            )
          })}
        </svg>
      </div>
    </div>
  )
}
