import type { CheckInInput } from '../api/client'
import { METRICS, type MetricKey } from '../util/format'

interface Props {
  value: CheckInInput
  onChange: (v: CheckInInput) => void
}

const HINTS: Record<MetricKey, string> = {
  mood: '1 매우 나쁨 · 10 매우 좋음',
  stress: '1 거의 없음 · 10 극심함',
  anxiety: '1 거의 없음 · 10 극심함',
  energy: '1 바닥 · 10 넘침',
  sleep: '1 매우 불만족 · 10 매우 만족',
}

export default function CheckInForm({ value, onChange }: Props) {
  const set = (key: MetricKey, n: number | null) => onChange({ ...value, [key]: n })
  return (
    <div>
      {METRICS.map((m) => {
        const current = value[m.key] ?? null
        return (
          <div className="checkin-row" key={m.key}>
            <div>
              <div>{m.label}</div>
              <div className="muted small">{HINTS[m.key]}</div>
            </div>
            <div className="scale" role="radiogroup" aria-label={m.label}>
              {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
                <button
                  type="button"
                  key={n}
                  className={current === n ? 'on' : ''}
                  aria-pressed={current === n}
                  onClick={() => set(m.key, current === n ? null : n)}
                >
                  {n}
                </button>
              ))}
              <button type="button" className={'skip' + (current === null ? ' on' : '')} onClick={() => set(m.key, null)}>
                건너뛰기
              </button>
            </div>
          </div>
        )
      })}
      <label className="field" style={{ marginTop: 16 }}>
        <span>메모 (선택) — 이번 주를 한두 줄로</span>
        <textarea value={value.note ?? ''} onChange={(e) => onChange({ ...value, note: e.target.value })} rows={2} />
      </label>
    </div>
  )
}
