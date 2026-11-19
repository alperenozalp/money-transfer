import type { TransferFormValues } from './validation'

const KEY_STORAGE_NAME = 'money-transfer.pending-key'
const FINGERPRINT_STORAGE_NAME = 'money-transfer.pending-fingerprint'

export async function getPendingTransferKey(values: TransferFormValues): Promise<string> {
  if (!crypto.subtle) throw new Error('Secure browser cryptography is required to create a transfer.')

  const normalized = JSON.stringify({
    sourceAccountId: values.sourceAccountId,
    receiverIban: values.receiverIban.trim().toUpperCase(),
    amount: canonicalAmount(values.amount),
    description: values.description.trim(),
  })
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(normalized))
  const fingerprint = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
  const savedFingerprint = sessionStorage.getItem(FINGERPRINT_STORAGE_NAME)
  const savedKey = sessionStorage.getItem(KEY_STORAGE_NAME)

  if (savedFingerprint === fingerprint && savedKey) return savedKey

  const key = crypto.randomUUID()
  sessionStorage.setItem(FINGERPRINT_STORAGE_NAME, fingerprint)
  sessionStorage.setItem(KEY_STORAGE_NAME, key)
  return key
}

export function clearPendingTransferKey(): void {
  sessionStorage.removeItem(FINGERPRINT_STORAGE_NAME)
  sessionStorage.removeItem(KEY_STORAGE_NAME)
}

function canonicalAmount(value: string): string {
  const [whole = '0', fraction = ''] = value.split('.')
  return `${BigInt(whole)}.${fraction.padEnd(2, '0')}`
}
