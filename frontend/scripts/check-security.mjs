// Static guard for the frontend: fails if a risky construct sneaks into src/.
// Run with: npm run test:security
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../src', import.meta.url))
const forbidden = [
  { re: /dangerouslySetInnerHTML/, why: 'raw HTML rendering of untrusted text' },
  { re: /\binnerHTML\b/, why: 'raw HTML rendering of untrusted text' },
  { re: /\beval\(/, why: 'dynamic code execution' },
  { re: /new Function\(/, why: 'dynamic code execution' },
  { re: /localStorage|sessionStorage|indexedDB/, why: 'browser storage must not hold tokens or counseling data' },
  { re: /document\.cookie/, why: 'cookies are not used by this app' },
  { re: /https?:\/\/(?!chatgpt\.com\/settings\/usage)[a-z0-9.-]+\.[a-z]{2,}/i, why: 'only the official ChatGPT usage link may be referenced' },
  { re: /refresh_?token|access_?token|(?<!invalid_)id_?token/i, why: 'the browser must never handle OAuth tokens' },
]

const files = []
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name)
    if (statSync(p).isDirectory()) walk(p)
    else if (/\.(ts|tsx|css)$/.test(name)) files.push(p)
  }
}
walk(root)

let failures = 0
for (const file of files) {
  const text = readFileSync(file, 'utf8')
  for (const { re, why } of forbidden) {
    const m = text.match(re)
    if (m) {
      failures++
      console.error(`FAIL ${file}: '${m[0]}' (${why})`)
    }
  }
}
if (failures) {
  console.error(`${failures} security check(s) failed`)
  process.exit(1)
}
console.log(`security check passed for ${files.length} files`)
