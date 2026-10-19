// Regression tests for the composer draft autosave. Run with: npm run test:unit (Node >= 22.18 strips types).
import assert from 'node:assert/strict'
import { DraftSaver, type SaveFn } from '../src/util/draftSaver.ts'

type Call = { text: string; keepalive: boolean; rev: number }

/** Server double with the same rule as DraftRepository.saveIfActive: only a newer revision replaces the row. */
class FakeServer {
  text = ''
  rev = 0
  apply(c: Call) {
    if (c.rev > this.rev) {
      this.text = c.text
      this.rev = c.rev
    }
  }
}

function harness(failFirst = 0) {
  const calls: Call[] = []
  let failures = failFirst
  const timers: (() => void)[] = []
  const states: boolean[] = []
  const server = new FakeServer()
  const saver = new DraftSaver({
    save: (text, keepalive, rev) => {
      calls.push({ text, keepalive, rev })
      if (failures > 0) {
        failures--
        return Promise.reject(new Error('network down'))
      }
      server.apply({ text, keepalive, rev })
      return Promise.resolve()
    },
    retryMs: 1000,
    setTimer: (fn) => timers.push(fn),
    clearTimer: () => {},
    onStateChange: (f) => states.push(f),
  })
  return { saver, calls, timers, states, server }
}

/** Requests that resolve only when the test says so, in any order. */
function manual() {
  const calls: (Call & { resolve: () => void; reject: () => void })[] = []
  const server = new FakeServer()
  const save: SaveFn = (text, keepalive, rev) =>
    new Promise<void>((resolve, reject) => {
      const call = { text, keepalive, rev, resolve: () => { server.apply(call); resolve() }, reject: () => reject(new Error('x')) }
      calls.push(call)
    })
  return { calls, server, saver: new DraftSaver({ save, retryMs: 0 }) }
}

const tick = () => new Promise((r) => setTimeout(r, 0))

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
    await tick()
    assert.equal(calls.length, 2)
    assert.equal(saver.pending, false)
    assert.deepEqual(states, [true, false])
  }],
  ['unchanged text that the server confirmed is not sent again', async () => {
    const { saver, calls } = harness()
    await saver.flush('a')
    await saver.flush('a')
    await saver.flush('a', true)
    assert.equal(calls.length, 1)
  }],
  ['a restored or dropped draft counts as saved', async () => {
    const { saver, calls } = harness()
    saver.markSaved('서버에 있던 초안')
    await saver.flush('서버에 있던 초안', true)
    saver.markSaved('') // message sent; the server removed the draft
    await saver.flush('')
    assert.equal(calls.length, 0)
  }],
  ['saves are serialized and the latest edit is sent after a slow request', async () => {
    const { saver, calls, server } = manual()
    const first = saver.flush('이전 초안')
    void saver.flush('수정된 초안')         // typed while the first request is slow
    assert.equal(calls.length, 1)           // not sent concurrently
    calls[0].resolve()
    await tick()
    assert.equal(calls.length, 2)
    assert.equal(calls[1].text, '수정된 초안')
    calls[1].resolve()
    await first
    assert.equal(server.text, '수정된 초안')
    assert.equal(saver.pending, false)
  }],
  ['a keepalive that overtakes a slow request still wins on the server', async () => {
    const { saver, calls, server } = manual()
    void saver.flush('이전 초안')
    const exit = saver.flush('마지막 초안', true) // tab closes while the first request is running
    assert.equal(calls.length, 2)
    assert.ok(calls[1].rev > calls[0].rev)
    calls[1].resolve()                         // keepalive arrives first …
    await exit
    calls[0].resolve()                         // … the old request lands afterwards
    await tick()
    assert.equal(server.text, '마지막 초안')
  }],
  ['revisions strictly increase even within the same millisecond', async () => {
    const saver = new DraftSaver({ save: () => Promise.resolve(), now: () => 1000 })
    const a = saver.nextRev()
    const b = saver.nextRev()
    assert.ok(b > a)
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
  ['an effect restart (React StrictMode) gets a working saver', async () => {
    // SessionPage creates the saver inside the effect and disposes it in the cleanup, exactly like this:
    const { calls, server } = harness()
    const make = () => new DraftSaver({ save: (t, k, r) => { calls.push({ text: t, keepalive: k, rev: r }); server.apply({ text: t, keepalive: k, rev: r }); return Promise.resolve() }, retryMs: 0 })
    let current = make()                   // mount
    await current.flush('', true)          // cleanup flush with an empty composer sends nothing
    current.dispose()
    current = make()                       // StrictMode re-runs the effect
    await current.flush('자동 저장되어야 하는 글')
    assert.equal(server.text, '자동 저장되어야 하는 글')
    assert.equal(calls.length, 1)
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
