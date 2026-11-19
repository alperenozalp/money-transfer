export interface TransferFormValues {
  sourceAccountId: string
  receiverIban: string
  amount: string
  description: string
}

export function validateTransfer(values: TransferFormValues): string | null {
  if (!values.sourceAccountId) return 'Havale yapmak için önce bir kaynak hesap seçin.'
  if (!values.receiverIban.trim() || values.receiverIban.trim().length > 34) {
    return 'Alıcı IBAN bilgisini kontrol edin.'
  }
  if (values.description.length > 500) return 'Açıklama 500 karakterden uzun olamaz.'

  const amountMatch = /^(0|[1-9]\d{0,16})(?:\.(\d{1,2}))?$/.exec(values.amount)
  if (!amountMatch) return 'Tutarı en fazla iki ondalık basamakla girin.'

  const cents = BigInt(amountMatch[1]) * 100n + BigInt((amountMatch[2] ?? '').padEnd(2, '0') || '0')
  return cents > 0n ? null : 'Transfer tutarı sıfırdan büyük olmalı.'
}
