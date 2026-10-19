/**
 * Text files attached to a counseling message. The browser reads the file and sends only its text;
 * limits mirror backend com.mentalcare.counseling.Attachments.
 */
export const MAX_FILES = 3
export const MAX_TOTAL_CHARS = 60_000
export const ACCEPT = '.txt,.md,text/plain,text/markdown'

export interface PendingFile {
  name: string
  content: string
  chars: number
}

export function isAllowedName(name: string): boolean {
  const lower = name.toLowerCase()
  return lower.endsWith('.txt') || lower.endsWith('.md')
}

/**
 * Decodes as UTF-8; files saved by older Windows tools in the Korean code page (CP949/EUC-KR) are
 * decoded with that instead of turning into replacement characters.
 */
export function decodeText(bytes: Uint8Array): string {
  let text: string
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  } catch {
    text = new TextDecoder('euc-kr').decode(bytes)
  }
  if (text.startsWith('﻿')) text = text.slice(1)
  return text.replace(/\r\n?/g, '\n')
}

/** Adds files to the pending list or returns a message explaining why not. */
export function addFiles(current: PendingFile[], incoming: PendingFile[]): { files: PendingFile[]; error?: string } {
  const files = [...current]
  for (const f of incoming) {
    if (!isAllowedName(f.name)) return { files: current, error: `텍스트(.txt) 또는 Markdown(.md) 파일만 첨부할 수 있습니다: ${f.name}` }
    if (!f.content.trim()) return { files: current, error: `내용이 비어 있는 파일입니다: ${f.name}` }
    if (files.length >= MAX_FILES) return { files: current, error: `파일은 한 번에 ${MAX_FILES}개까지 첨부할 수 있습니다.` }
    files.push(f)
  }
  const total = files.reduce((n, f) => n + f.chars, 0)
  if (total > MAX_TOTAL_CHARS) {
    return { files: current, error: `첨부 파일이 너무 깁니다. 한 메시지에 모두 합쳐 ${MAX_TOTAL_CHARS.toLocaleString()}자까지 첨부할 수 있습니다.` }
  }
  return { files }
}

export async function readFile(file: File): Promise<PendingFile> {
  const content = decodeText(new Uint8Array(await file.arrayBuffer()))
  return { name: file.name, content, chars: content.length }
}
