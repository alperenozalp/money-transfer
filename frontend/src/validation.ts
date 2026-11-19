export interface TransferFormValues {
  sourceAccountId: string
  receiverIban: string
  amount: string
  description: string
}

export function validateTransfer(values: TransferFormValues): string | null {
  if (!values.sourceAccountId) return 'Select a source account before creating a transfer.'
  if (!values.receiverIban.trim() || values.receiverIban.trim().length > 34) {
    return 'Enter a valid recipient IBAN.'
  }
  if (values.description.length > 500) return 'Description cannot exceed 500 characters.'

  const amountMatch = /^(0|[1-9]\d{0,16})(?:\.(\d{1,2}))?$/.exec(values.amount)
  if (!amountMatch) return 'Enter an amount with no more than two decimal places.'

  const cents = BigInt(amountMatch[1]) * 100n + BigInt((amountMatch[2] ?? '').padEnd(2, '0') || '0')
  return cents > 0n ? null : 'Transfer amount must be greater than zero.'
}
