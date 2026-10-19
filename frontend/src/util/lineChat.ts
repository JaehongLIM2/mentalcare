/**
 * LINE chat exports ("[LINE] …님과의 대화.txt") are often far longer than one message may attach.
 * This splits an export into days so the user can attach a date range, and drops lines that carry no
 * text (photos, stickers, cancelled messages). Everything runs in the browser; nothing is stored.
 */
export interface LineDay {
  /** ISO date, e.g. 2026-09-06 */
  date: string
  /** The export's own date line, kept as the day's heading */
  heading: string
  lines: string[]
}

export interface LineChat {
  /** Lines before the first date (export title, "saved on" line) */
  header: string[]
  days: LineDay[]
}

// 2026/09/06(토) · 2026.09.06 토요일 · 2026. 9. 6. (토) · 2026-09-06 · 2026년 9월 6일 토요일
const DATE_LINE =
  /^\s*(\d{4})\s*(?:[./-]|년)\s*(\d{1,2})\s*(?:[./-]|월)\s*(\d{1,2})\s*[.일]?\s*(?:\(\s*\S{1,4}\s*\)|[^\s\d\t]{1,4}요일|[A-Za-z]{3,9}|[月火水木金土日])?\s*$/
// 15:12<TAB>  · 오후 3:12<TAB>  · 3:12 PM<TAB>  · 午後3:12<TAB>
const MESSAGE_LINE = /^(?:오전|오후|AM|PM|午前|午後)?\s?\d{1,2}:\d{2}(?:\s?(?:AM|PM))?\t/
const NO_TEXT_BODIES = new Set([
  '사진', '스티커', '동영상', '파일', '이모티콘', '앨범', '연락처', '위치 정보', '위치정보', '음성 메시지', '음성메시지',
  'Photo', 'Photos', 'Sticker', 'Video', 'File', 'Album', 'Contact', 'Location', 'Voice message',
  '写真', 'スタンプ', '動画', 'ファイル', 'アルバム', '連絡先', '位置情報', 'ボイスメッセージ',
])
const CANCELLED = /(메시지(?:\s?송신)?을? 취소했습니다|unsent a message|メッセージの送信を取り消しました)/

export function parseLineChat(text: string): LineChat | null {
  const header: string[] = []
  const days: LineDay[] = []
  let messages = 0
  for (const line of text.split('\n')) {
    const d = DATE_LINE.exec(line)
    const date = d && !line.includes('\t') ? `${d[1]}-${d[2].padStart(2, '0')}-${d[3].padStart(2, '0')}` : null
    // Dates only move forward; a message line that is just a date stays part of its day.
    if (date && (days.length === 0 || date > days[days.length - 1].date)) {
      days.push({ date, heading: line.trim(), lines: [] })
      continue
    }
    if (days.length === 0) {
      header.push(line)
      continue
    }
    if (MESSAGE_LINE.test(line)) messages++
    days[days.length - 1].lines.push(line)
  }
  // A real export has date lines and time-stamped, tab-separated messages under them.
  if (days.length === 0 || messages < days.length) return null
  return { header: header.filter((l) => l.trim()), days }
}

/** True for a message line whose only content is a placeholder (photo, sticker) or a cancelled message. */
export function isNoTextLine(line: string): boolean {
  if (!MESSAGE_LINE.test(line)) return false
  const body = line.split('\t').slice(2).join('\t').trim()
  const bracketed = /^\[(.+)\]$/.exec(body)
  return (bracketed !== null && NO_TEXT_BODIES.has(bracketed[1].trim())) || NO_TEXT_BODIES.has(body) || CANCELLED.test(line)
}

/** Text of days [from, to] (inclusive indexes), with the export header on top. */
export function lineRangeText(chat: LineChat, from: number, to: number, dropNoText: boolean): string {
  const out = [...chat.header]
  if (out.length) out.push('')
  for (let i = from; i <= to; i++) {
    const day = chat.days[i]
    out.push(day.heading)
    for (const l of day.lines) if (!(dropNoText && isNoTextLine(l))) out.push(l)
  }
  return out.join('\n').replace(/\n{3,}/g, '\n\n').trim() + '\n'
}

/** Earliest start index such that [start, last] fits in maxChars; -1 if not even the last day fits. */
export function latestFittingStart(chat: LineChat, maxChars: number, dropNoText: boolean): number {
  const last = chat.days.length - 1
  if (last < 0 || lineRangeText(chat, last, last, dropNoText).length > maxChars) return -1
  let lo = 0
  let hi = last
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (lineRangeText(chat, mid, last, dropNoText).length <= maxChars) hi = mid
    else lo = mid + 1
  }
  return lo
}

/** "name.txt" -> "name (2026-09-06~2026-09-10).txt" */
export function rangeFileName(name: string, chat: LineChat, from: number, to: number): string {
  const dot = name.lastIndexOf('.')
  const base = dot > 0 ? name.slice(0, dot) : name
  const ext = dot > 0 ? name.slice(dot) : '.txt'
  const a = chat.days[from].date
  const b = chat.days[to].date
  return `${base} (${a === b ? a : `${a}~${b}`})${ext}`
}
