export async function getPendingTransferKey(_values?: unknown): Promise<string> {
  return crypto.randomUUID()
}

export function clearPendingTransferKey(): void {
  // No-op: Each transfer request generates a fresh UUID.
}
