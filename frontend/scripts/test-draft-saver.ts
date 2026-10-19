// Regression tests for the composer draft autosave. Run with: npm run test:unit (Node >= 22.18 strips types).
import assert from 'node:assert/strict'
import { DraftSaver } from '../src/util/draftSaver.ts'

type Call = { text: string; keepalive: boolean }

function harness(failFirst: number) {
  const calls: Call[] = []
  let failures = failFirst
  const timers: (() => void)[] = []
  const states: boolean[] = []
  const saver = new DraftSaver({
    save: (text, keepalive) => {
      calls.push({ text, keepalive })
      if (failures > 0) {
        failures--
        return Promise.reject(new Error('network down'))
      }
      return Promise.resolve()
    },
    retryMs: 1000,
    setTimer: (fn) => timers.push(fn),
    clearTimer: () => {},
    onStateChange: (f) => states.push(f),
  })
  return { saver, calls, timers, states }
}

const tests: [string, () => Promise<void>][] = [
  ['a failed save is retried when the page is left without further edits', async () => {
    const { saver, calls } = harness(1)
    await saver.flush('긴 초안')            // debounce save fails (server unreachable)
    assert.equal(saver.pending, true)
    await saver.flush('긴 초안', true)      // pagehide after the server came back
    assert.deepEqual(calls.map((c) => c.keepalive), [false, true])
    assert.equal(saver.pending, false)
  }],
  ['a failed save is retried by the timer', async () => {
    const { saver, calls, timers, states } = harness(1)
    await saver.flush('초안')
    assert.equal(timers.length, 1)
    timers.shift()!()
    await new Promise((r) => setTimeout(r, 0))
    assert.equal(calls.length, 2)
    assert.equal(saver.pending, false)
    assert.deepEqual(states, [true, false])
  }],
  ['unchanged text that the server confirmed is not sent again', async () => {
    const { saver, calls } = harness(0)
    await saver.flush('a')
    await saver.flush('a')
    await saver.flush('a', true)
    assert.equal(calls.length, 1)
  }],
  ['a restored or dropped draft counts as saved', async () => {
    const { saver, calls } = harness(0)
    saver.markSaved('서버에 있던 초안')
    await saver.flush('서버에 있던 초안', true)
    saver.markSaved('') // message sent; the server removed the draft
    await saver.flush('')
    assert.equal(calls.length, 0)
  }],
  ['the same text is not sent twice while in flight, except as a keepalive on exit', async () => {
    const calls: Call[] = []
    let release!: () => void
    const saver = new DraftSaver({
      save: (text, keepalive) => {
        calls.push({ text, keepalive })
        return keepalive ? Promise.resolve() : new Promise<void>((r) => { release = r })
      },
      retryMs: 0,
    })
    const first = saver.flush('x')
    void saver.flush('x')
    assert.equal(calls.length, 1)
    await saver.flush('x', true)
    assert.equal(calls.length, 2)
    release()
    await first
    assert.equal(saver.pending, false)
  }],
  ['after dispose only keepalive flushes go out and no timer is scheduled', async () => {
    const { saver, calls, timers } = harness(5)
    saver.dispose()
    await saver.flush('a')
    assert.equal(calls.length, 0)
    await saver.flush('a', true)
    assert.equal(calls.length, 1)
    assert.equal(timers.length, 0)
  }],
]

let failed = 0
for (const [name, fn] of tests) {
  try {
    await fn()
    console.log(`ok   ${name}`)
  } catch (e) {
    failed++
    console.error(`FAIL ${name}\n     ${(e as Error).message}`)
  }
}
if (failed) {
  console.error(`${failed} of ${tests.length} draft saver tests failed`)
  process.exit(1)
}
console.log(`${tests.length} draft saver tests passed`)
