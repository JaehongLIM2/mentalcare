/**
 * Keeps the server copy of the composer text in sync.
 *
 * - A text counts as saved only after the server confirmed it; a failed save stays pending and is
 *   retried (on the next flush and on a timer).
 * - Normal saves are serialized: at most one request is in flight, and whatever was typed meanwhile is
 *   sent right after it finishes, so the last write is always the latest text.
 * - Every request carries a strictly increasing revision. The server keeps only the newest revision, so a
 *   keepalive sent while the tab closes (which may overtake a running request) still wins, and a request
 *   that arrives late can never restore older text.
 *
 * One instance belongs to one mounted page; create a new one when the page (or React effect) restarts.
 */
export type SaveFn = (text: string, keepalive: boolean, rev: number) => Promise<void>

export interface DraftSaverOptions {
  save: SaveFn
  /** Delay before retrying a failed save; 0 disables the timer (tests). */
  retryMs?: number
  onStateChange?: (failing: boolean) => void
  setTimer?: (fn: () => void, ms: number) => number
  clearTimer?: (id: number) => void
  now?: () => number
}

export class DraftSaver {
  private saved = ''
  private latest = ''
  private running: Promise<void> | null = null
  private failing = false
  private timer: number | null = null
  private disposed = false
  private lastRev = 0
  private readonly save: SaveFn
  private readonly retryMs: number
  private readonly onStateChange?: (failing: boolean) => void
  private readonly setTimer: (fn: () => void, ms: number) => number
  private readonly clearTimer: (id: number) => void
  private readonly now: () => number

  constructor(opts: DraftSaverOptions) {
    this.save = opts.save
    this.retryMs = opts.retryMs ?? 5000
    this.onStateChange = opts.onStateChange
    this.setTimer = opts.setTimer ?? ((fn, ms) => setTimeout(fn, ms) as unknown as number)
    this.clearTimer = opts.clearTimer ?? ((id) => clearTimeout(id))
    this.now = opts.now ?? (() => Date.now())
  }

  /** Revision newer than every request issued so far (microsecond clock, same PC as the server). */
  nextRev(): number {
    this.lastRev = Math.max(this.now() * 1000, this.lastRev + 1)
    return this.lastRev
  }

  /** The server already holds this text (draft restored on load) or dropped it (message sent: ''). */
  markSaved(text: string): void {
    this.saved = text
    this.latest = text
    this.setFailing(false)
  }

  /** Saves {@code text} unless the server already has it. Resolves when the queue is idle again. */
  flush(text: string, keepalive = false): Promise<void> {
    this.latest = text
    if (keepalive) return this.sendKeepalive(text)
    if (this.disposed) return Promise.resolve()
    if (this.running) return this.running // the loop picks up `latest` when the current request is done
    if (text === this.saved) return Promise.resolve()
    this.running = this.loop().finally(() => {
      this.running = null
    })
    return this.running
  }

  /** True while the latest text is not confirmed by the server. */
  get pending(): boolean {
    return this.latest !== this.saved
  }

  dispose(): void {
    this.disposed = true
    if (this.timer !== null) this.clearTimer(this.timer)
    this.timer = null
  }

  private async loop(): Promise<void> {
    while (!this.disposed && this.latest !== this.saved) {
      const text = this.latest
      try {
        await this.save(text, false, this.nextRev())
        this.saved = text
      } catch {
        this.setFailing(true)
        this.scheduleRetry()
        return
      }
    }
    if (this.latest === this.saved) this.setFailing(false)
  }

  /** Page is going away: send now with the newest revision, regardless of a request still running. */
  private sendKeepalive(text: string): Promise<void> {
    if (text === this.saved && !this.running) return Promise.resolve()
    return this.save(text, true, this.nextRev()).then(
      () => {
        if (this.latest === text) {
          this.saved = text
          this.setFailing(false)
        }
      },
      () => {
        this.setFailing(true)
        this.scheduleRetry()
      },
    )
  }

  private scheduleRetry(): void {
    if (this.disposed || !this.retryMs || this.timer !== null) return
    this.timer = this.setTimer(() => {
      this.timer = null
      void this.flush(this.latest)
    }, this.retryMs)
  }

  private setFailing(value: boolean): void {
    if (this.failing === value) return
    this.failing = value
    this.onStateChange?.(value)
  }
}
