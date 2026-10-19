// Unit tests for splitting LINE chat exports by day. Run with: npm run test:unit
import assert from 'node:assert/strict'
import { isNoTextLine, latestFittingStart, lineRangeText, parseLineChat, rangeFileName } from '../src/util/lineChat.ts'

const pcExport = [
  '[LINE] 친구님과의 대화',
  '저장한 날짜 : 2026/10/19 18:08',
  '',
  '2026/09/06(토)',
  '15:12\t나\t안녕',
  '15:13\t친구\t[사진]',
  '15:14\t친구\t"여러 줄',
  '두 번째 줄"',
  '',
  '2026/09/07(일)',
  '09:01\t나\t[스티커]',
  '09:02\t친구\t메시지 송신을 취소했습니다.',
  '09:03\t나\t좋은 아침',
  '',
  '2026/09/08(월)',
  '21:40\t친구\t내일 봐',
].join('\n')

const tests: [string, () => void][] = [
  ['a PC export is split into days with the header kept', () => {
    const chat = parseLineChat(pcExport)!
    assert.ok(chat)
    assert.deepEqual(chat.header, ['[LINE] 친구님과의 대화', '저장한 날짜 : 2026/10/19 18:08'])
    assert.deepEqual(chat.days.map((d) => d.date), ['2026-09-06', '2026-09-07', '2026-09-08'])
    assert.ok(chat.days[0].lines.includes('두 번째 줄"'), 'continuation lines stay with their day')
    const quoted = parseLineChat(pcExport.replace('두 번째 줄"', '2026/09/06\n두 번째 줄"'))!
    assert.equal(quoted.days.length, 3, 'a date written inside a message does not start a new day')
  }],
  ['other date and time styles are recognised', () => {
    const mobile = '2026.09.06 토요일\n오후 3:12\t나\t안녕\n2026. 9. 7. (일)\n오전 9:01\t친구\t응\n2026년 9월 8일 월요일\n3:12 PM\t나\t잘 자'
    assert.deepEqual(parseLineChat(mobile)!.days.map((d) => d.date), ['2026-09-06', '2026-09-07', '2026-09-08'])
  }],
  ['ordinary notes with dates are not taken for a LINE export', () => {
    assert.equal(parseLineChat('회의 메모\n2026/09/06\n오늘 한 일 정리'), null)
    assert.equal(parseLineChat('그냥 긴 글입니다.\n'.repeat(100)), null)
  }],
  ['placeholder and cancelled lines are dropped only when asked', () => {
    assert.ok(isNoTextLine('15:13\t친구\t[사진]'))
    assert.ok(isNoTextLine('09:02\t친구\t메시지 송신을 취소했습니다.'))
    assert.ok(!isNoTextLine('15:12\t나\t[사진] 보낼게'))
    const chat = parseLineChat(pcExport)!
    const kept = lineRangeText(chat, 0, 2, false)
    const dropped = lineRangeText(chat, 0, 2, true)
    assert.ok(kept.includes('[사진]') && !dropped.includes('[사진]') && !dropped.includes('[스티커]'))
    assert.ok(dropped.includes('좋은 아침') && dropped.includes('2026/09/07(일)'))
  }],
  ['a range keeps the header and only the chosen days', () => {
    const text = lineRangeText(parseLineChat(pcExport)!, 1, 1, true)
    assert.ok(text.startsWith('[LINE] 친구님과의 대화'))
    assert.ok(text.includes('좋은 아침') && !text.includes('안녕') && !text.includes('내일 봐'))
  }],
  ['the latest range that fits is found, and file names carry the range', () => {
    const chat = parseLineChat(pcExport)!
    const all = lineRangeText(chat, 0, 2, true).length
    assert.equal(latestFittingStart(chat, all, true), 0)
    const lastTwo = lineRangeText(chat, 1, 2, true).length
    assert.equal(latestFittingStart(chat, lastTwo, true), 1)
    assert.equal(latestFittingStart(chat, 5, true), -1)
    assert.equal(rangeFileName('[LINE] 친구님과의 대화.txt', chat, 0, 1), '[LINE] 친구님과의 대화 (2026-09-06~2026-09-07).txt')
    assert.equal(rangeFileName('a.txt', chat, 2, 2), 'a (2026-09-08).txt')
  }],
]

let failed = 0
for (const [name, fn] of tests) {
  try {
    fn()
    console.log(`ok   ${name}`)
  } catch (e) {
    failed++
    console.error(`FAIL ${name}\n     ${(e as Error).message}`)
  }
}
if (failed) {
  console.error(`${failed} of ${tests.length} LINE chat tests failed`)
  process.exit(1)
}
console.log(`${tests.length} LINE chat tests passed`)
