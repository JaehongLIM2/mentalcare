export function fmtDateTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

export function fmtTime(iso?: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${p(d.getHours())}:${p(d.getMinutes())}`
}

export const METRICS = [
  { key: 'mood', label: '기분' },
  { key: 'stress', label: '스트레스' },
  { key: 'anxiety', label: '불안' },
  { key: 'energy', label: '에너지' },
  { key: 'sleep', label: '수면' },
] as const

export type MetricKey = (typeof METRICS)[number]['key']

export const CATEGORY_LABEL: Record<string, string> = {
  concern: '고민',
  pattern: '패턴',
  relationship: '관계',
  goal: '목표',
  event: '사건',
  change: '변화',
  other: '기타',
}
