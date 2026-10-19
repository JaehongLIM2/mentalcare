/**
 * Keeps the server copy of the composer text in sync. A text counts as saved only after the server
 * confirmed it; a failed save stays pending and is retried (on the next flush and on a timer), so a
 * short connection loss followed by leaving the page without further edits does not lose the draft.
 */
export interface DraftSaverOptions {
  save: (text: string, keepalive: boolean) => Promise<void>
  /** Delay before retrying a failed save; 0 disables the timer (tests). */
  retryMs?: number
  onStateChange?: (failing: boolean) => void
  setTimer?: (fn: () => void, ms: number) => number
  clearTimer?: (id: number) => void
}

export class DraftSaver {
  private saved = ''
  private inFlight: string | null = null
  private failing = false
  private timer: number | null = null
  private latest = ''
  private disposed = false
  private readonly opts: Required<Omit<DraftSaverOptions, 'onStateChange'>> & Pick<DraftSaverOptions, 'onStateChange'>

  constructor(opts: DraftSaverOptions) {
    this.opts = {
      retryMs: 5000,
      setTimer: (fn, ms) => setTimeout(fn, ms) as unknown as number,
      clearTimer: (id) => clearTimeout(id),
      ...opts,
    }
  }

  /** The server already holds this text (draft restored on load) or dropped it (message sent: ''). */
  markSaved(text: string): void {
    this.saved = text
    this.latest = text
    this.setFailing(false)
  }

  /** Saves {@code text} unless the server already has it or the very same save is running. */
  flush(text: string, keepalive = false): Promise<void> {
    this.latest = text
    if (this.disposed && !keepalive) return Promise.resolve()
    if (text === this.saved) return Promise.resolve()
    // a keepalive flush (tab closing) is sent even if a normal request for the same text is still running,
    // because the browser may cancel that one when the page goes away
    if (text === this.inFlight && !keepalive) return Promise.resolve()
    this.inFlight = text
    return this.opts.save(text, keepalive).then(
      () => {
        this.saved = text
        if (this.inFlight === text) this.inFlight = null
        if (this.latest === text) this.setFailing(false)
      },
      () => {
        if (this.inFlight === text) this.inFlight = null
        this.setFailing(true)
        this.scheduleRetry()
      },
    )
  }

  /** True while the latest text is not confirmed by the server. */
  get pending(): boolean {
    return this.latest !== this.saved
  }

  dispose(): void {
    this.disposed = true
    if (this.timer !== null) this.opts.clearTimer(this.timer)
    this.timer = null
  }

  private scheduleRetry(): void {
    if (this.disposed || !this.opts.retryMs || this.timer !== null) return
    this.timer = this.opts.setTimer(() => {
      this.timer = null
      void this.flush(this.latest)
    }, this.opts.retryMs)
  }

  private setFailing(value: boolean): void {
    if (this.failing === value) return
    this.failing = value
    this.opts.onStateChange?.(value)
  }
}
