// Unit tests for reading and limiting attached text files. Run with: npm run test:unit
import assert from 'node:assert/strict'
import { addFiles, decodeText, MAX_FILES, MAX_TOTAL_CHARS, type PendingFile } from '../src/util/attachments.ts'

const f = (name: string, content: string): PendingFile => ({ name, content, chars: content.length })

const tests: [string, () => void][] = [
  ['UTF-8 text is decoded, BOM and CRLF are normalised', () => {
    const bytes = new Uint8Array([0xef, 0xbb, 0xbf, ...new TextEncoder().encode('첫 줄\r\n둘째 줄')])
    assert.equal(decodeText(bytes), '첫 줄\n둘째 줄')
  }],
  ['CP949 (EUC-KR) files from older Windows tools are readable', () => {
    // "안녕" in EUC-KR
    assert.equal(decodeText(new Uint8Array([0xbe, 0xc8, 0xb3, 0xe7])), '안녕')
  }],
  ['only .txt and .md are accepted', () => {
    assert.ok(addFiles([], [f('a.TXT', 'x')]).files.length === 1)
    assert.ok(addFiles([], [f('b.md', 'x')]).files.length === 1)
    assert.match(addFiles([], [f('c.pdf', 'x')]).error ?? '', /\.txt/)
  }],
  ['empty files, too many files and too much text are refused without changing the list', () => {
    const start = [f('a.txt', 'a')]
    assert.match(addFiles(start, [f('e.txt', '   ')]).error ?? '', /비어/)
    const many = Array.from({ length: MAX_FILES }, (_, i) => f(`${i}.txt`, 'x'))
    const r = addFiles(many, [f('extra.txt', 'x')])
    assert.match(r.error ?? '', /개까지/)
    assert.equal(r.files, many)
    assert.match(addFiles([], [f('big.txt', '가'.repeat(MAX_TOTAL_CHARS + 1))]).error ?? '', /너무 깁니다/)
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
  console.error(`${failed} of ${tests.length} attachment tests failed`)
  process.exit(1)
}
console.log(`${tests.length} attachment tests passed`)
