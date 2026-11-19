import { describe, expect, it } from 'vitest'
import { validateTransfer, type TransferFormValues } from './validation'

const validTransfer: TransferFormValues = {
  sourceAccountId: 'account-id',
  receiverIban: 'TR0000000000000000000000002',
  amount: '125.00',
  description: 'Kira ödemesi',
}

describe('transfer form validation', () => {
  it('accepts a positive amount with up to two decimal places', () => {
    expect(validateTransfer(validTransfer)).toBeNull()
  })

  it.each(['1.234', '-5', '1e3', '123456789012345678'])(
    'rejects invalid amount %s',
    (amount) => {
      expect(validateTransfer({ ...validTransfer, amount })).toContain('Tutarı')
    },
  )

  it('rejects a zero transfer amount', () => {
    expect(validateTransfer({ ...validTransfer, amount: '0' })).toContain('sıfırdan büyük')
  })

  it('requires an internal receiver IBAN and limits the description length', () => {
    expect(validateTransfer({ ...validTransfer, receiverIban: '' })).toContain('IBAN')
    expect(validateTransfer({ ...validTransfer, description: 'x'.repeat(501) })).toContain('500 karakter')
  })
})
