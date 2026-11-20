export function formatMoney(value: string, currency = 'TRY'): string {
  const [whole = '0', fraction = '00'] = value.split('.')
  const groupedWhole = new Intl.NumberFormat('en-GB').format(BigInt(whole))
  const formattedAmount = `${groupedWhole}.${fraction.padEnd(2, '0').slice(0, 2)}`
  return `${currency} ${formattedAmount}`
}

export function maskIban(iban: string): string {
  const clean = iban.replace(/\s/g, '')
  return `${clean.slice(0, 4)} •••• •••• •••• ${clean.slice(-4)}`
}

export function formatIbanBlocks(iban: string): string {
  const clean = iban.replace(/\s/g, '').toUpperCase()
  return clean.match(/.{1,4}/g)?.join(' ') ?? clean
}

export function formatAmountInput(value: string): string {
  if (!value) return ''

  // Virgülleri kaldır (önceki binlik ayraçları)
  const raw = value.replace(/,/g, '')

  // Sadece rakam ve tek bir nokta kabul et
  const cleaned = raw.replace(/[^0-9.]/g, '')
  if (!cleaned) return ''

  const parts = cleaned.split('.')
  const wholePart = parts[0] || ''
  const decimalPart = parts.length > 1 ? parts.slice(1).join('').slice(0, 2) : null

  // Her 3 basamakta bir binlik virgülü ekle
  const formattedWhole = wholePart ? wholePart.replace(/\B(?=(\d{3})+(?!\d))/g, ',') : '0'

  if (decimalPart !== null) {
    return `${formattedWhole}.${decimalPart}`
  }
  if (cleaned.endsWith('.')) {
    return `${formattedWhole}.`
  }
  return formattedWhole
}

export function normalizeAmountOnBlur(value: string): string {
  if (!value || !value.trim()) return ''

  const raw = value.replace(/,/g, '').trim()
  if (!raw || raw === '.') return ''

  const parts = raw.split('.')
  const wholePart = parts[0] || '0'
  const decimalPart = parts.length > 1 ? parts[1].padEnd(2, '0').slice(0, 2) : '00'

  const formattedWhole = wholePart.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return `${formattedWhole}.${decimalPart}`
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
