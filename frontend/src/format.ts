export function formatMoney(value: string, currency = 'TRY'): string {
  const [whole = '0', fraction = '00'] = value.split('.')
  const groupedWhole = new Intl.NumberFormat('en-GB').format(BigInt(whole))
  const formattedAmount = `${groupedWhole}.${fraction.padEnd(2, '0').slice(0, 2)}`
  return `${currency} ${formattedAmount}`
}

export function maskIban(iban: string): string {
  return `${iban.slice(0, 4)} •••• •••• •••• ${iban.slice(-4)}`
}

export function formatDate(value: string): string {
  return new Intl.DateTimeFormat('en-GB', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Europe/Istanbul',
  }).format(new Date(value))
}

export function getInitials(value: string): string {
  const parts = value.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return 'MT'
  return parts.slice(0, 2).map((part) => part[0]?.toLocaleUpperCase('en-GB') ?? '').join('')
}
