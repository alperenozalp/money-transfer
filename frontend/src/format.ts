export function formatMoney(value: string, currency = 'TRY'): string {
  const [whole = '0', fraction = '00'] = value.split('.')
  const groupedWhole = new Intl.NumberFormat('tr-TR').format(BigInt(whole))
  const formattedAmount = `${groupedWhole},${fraction.padEnd(2, '0').slice(0, 2)}`
  return currency === 'TRY' ? `${formattedAmount} ₺` : `${formattedAmount} ${currency}`
}

export function maskIban(iban: string): string {
  return `${iban.slice(0, 4)} •••• •••• •••• ${iban.slice(-4)}`
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('tr-TR', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Europe/Istanbul',
  }).format(new Date(value))
}

export function getInitials(value: string): string {
  const parts = value.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return 'MT'
  return parts.slice(0, 2).map((part) => part[0]?.toLocaleUpperCase('tr-TR') ?? '').join('')
}
