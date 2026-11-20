import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  ApiError,
  createTransfer,
  getAccounts,
  getMonthlyStatistics,
  getTransfer,
  getTransferLimits,
  getTransfers,
  type Account,
  type MonthlyTransferStatistic,
  type Transfer,
  type TransferDirection,
  type TransferLimits,
  type TransferPageMeta,
} from './api'
import {
  clearSessionUser,
  finishSignIn,
  getCurrentUser,
  isAuthenticationEnabled,
  isAuthCallback,
  isDemoAuthEnabled,
  signIn,
  signOut,
  type DemoCredentials,
  type AuthSession,
} from './auth'
import { clearPendingTransferKey, getPendingTransferKey } from './pendingTransferKey'
import { formatDate, formatIbanBlocks, formatAmountInput, formatMoney, maskIban } from './format'
import { validateTransfer, type TransferFormValues } from './validation'
import './App.css'

type View = 'accounts' | 'transfers'

const EMPTY_FORM: TransferFormValues = {
  sourceAccountId: '',
  receiverIban: '',
  amount: '',
  description: '',
}
const PAGE_SIZE = 10

export function App() {
  const [user, setUser] = useState<AuthSession | null>(null)
  const [authReady, setAuthReady] = useState(false)
  const [authError, setAuthError] = useState('')
  const [demoCustomerId, setDemoCustomerId] = useState('')
  const [demoPassword, setDemoPassword] = useState('')
  const [view, setView] = useState<View>('accounts')
  const [accounts, setAccounts] = useState<Account[]>([])
  const [limits, setLimits] = useState<TransferLimits | null>(null)
  const [statistics, setStatistics] = useState<MonthlyTransferStatistic[]>([])
  const [transfers, setTransfers] = useState<Transfer[]>([])
  const [pageMeta, setPageMeta] = useState<TransferPageMeta>({
    page: 0,
    size: PAGE_SIZE,
    totalElements: 0,
    totalPages: 0,
  })
  const [page, setPage] = useState(0)
  const [historyDirection, setHistoryDirection] = useState<TransferDirection | 'ALL'>('ALL')
  const [startDate, setStartDate] = useState('')
  const [endDate, setEndDate] = useState('')
  const [summaryLoading, setSummaryLoading] = useState(true)
  const [historyLoading, setHistoryLoading] = useState(true)
  const [summaryError, setSummaryError] = useState('')
  const [historyError, setHistoryError] = useState('')
  const [form, setForm] = useState(EMPTY_FORM)
  const [formError, setFormError] = useState('')
  const [formSuccess, setFormSuccess] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [selectedTransfer, setSelectedTransfer] = useState<Transfer | null>(null)
  const [detailError, setDetailError] = useState('')

  const accessToken = user?.access_token ?? ''
  const customerName = useMemo(() => {
    const name = user?.profile.name
    return typeof name === 'string' && name.trim() ? name : 'My account'
  }, [user])
  const totalBalance = useMemo(() => {
    const cents = accounts
      .filter((account) => account.currency === 'TRY')
      .reduce((sum, account) => sum + toCents(account.balance), 0n)
    return fromCents(cents)
  }, [accounts])
  const activeAccounts = accounts.filter(
    (account) => account.status === 'ACTIVE' && account.currency === 'TRY',
  )

  useEffect(() => {
    let active = true
    async function initialize() {
      try {
        const currentUser = isAuthCallback ? await finishSignIn() : await getCurrentUser()
        if (active) setUser(currentUser)
      } catch {
        if (active) setAuthError('Sign-in could not be completed. Please try again.')
      } finally {
        if (isAuthCallback) window.history.replaceState({}, document.title, '/')
        if (active) setAuthReady(true)
      }
    }

    void initialize()
    return () => {
      active = false
    }
  }, [])

  const refreshSummary = useCallback(async () => {
    if (!accessToken) return
    setSummaryLoading(true)
    setSummaryError('')
    try {
      const [accountList, currentLimits, monthlyStatistics] = await Promise.all([
        getAccounts(accessToken),
        getTransferLimits(accessToken),
        getMonthlyStatistics(accessToken),
      ])
      setAccounts(accountList)
      setLimits(currentLimits)
      setStatistics(monthlyStatistics)
      setForm((current) => {
        const activeAccounts = accountList.filter(
          (account) => account.status === 'ACTIVE' && account.currency === 'TRY',
        )
        const isCurrentValid = activeAccounts.some((account) => account.id === current.sourceAccountId)
        return {
          ...current,
          sourceAccountId: isCurrentValid ? current.sourceAccountId : (activeAccounts[0]?.id || ''),
        }
      })
    } catch (error) {
      setSummaryError(getFriendlyError(error))
    } finally {
      setSummaryLoading(false)
    }
  }, [accessToken])

  const refreshHistory = useCallback(async () => {
    if (!accessToken) return
    setHistoryLoading(true)
    setHistoryError('')
    try {
      const result = await getTransfers(accessToken, {
        page,
        size: PAGE_SIZE,
        direction: historyDirection === 'ALL' ? undefined : historyDirection,
        startDate: startDate || undefined,
        endDate: endDate || undefined,
      })
      setTransfers(result.transfers)
      setPageMeta(result.meta)
    } catch (error) {
      setHistoryError(getFriendlyError(error))
    } finally {
      setHistoryLoading(false)
    }
  }, [accessToken, endDate, historyDirection, page, startDate])

  useEffect(() => {
    void refreshSummary()
  }, [refreshSummary])

  useEffect(() => {
    void refreshHistory()
  }, [refreshHistory])

  async function handleSignIn(credentials?: DemoCredentials) {
    setAuthError('')
    try {
      const session = await signIn(credentials)
      if (session) {
        setUser(session)
        setDemoPassword('')
      }
    } catch (error) {
      setAuthError(isDemoAuthEnabled && error instanceof Error
        ? error.message
        : 'Could not connect to the identity provider. Check your OIDC settings.')
    }
  }

  async function handleSignOut() {
    clearPendingTransferKey()
    setForm(EMPTY_FORM)
    setAccounts([])
    setLimits(null)
    setStatistics([])
    setTransfers([])
    try {
      await signOut()
      setUser(null)
    } catch {
      await clearSessionUser()
      setUser(null)
      setAuthError('Could not sign out. Close the browser session and try again.')
    }
  }

  async function handleCreateTransfer(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setFormError('')
    setFormSuccess('')
    const validationMessage = validateTransfer(form)
    if (validationMessage) {
      setFormError(validationMessage)
      return
    }

    setSubmitting(true)
    try {
      const idempotencyKey = await getPendingTransferKey(form)
      const cleanReceiverIban = form.receiverIban.replace(/\s/g, '').toUpperCase()
      const normalizedAmount = form.amount.trim().replace(/,/g, '')
      const result = await createTransfer(accessToken, idempotencyKey, {
        sourceAccountId: form.sourceAccountId,
        receiverIban: cleanReceiverIban,
        amount: normalizedAmount,
        description: form.description.trim() || undefined,
      })
      clearPendingTransferKey()
      setForm(EMPTY_FORM)
      setFormSuccess(
        `Successfully sent ${formatMoney(result.amount, result.currency)} to ${maskIban(result.receiverIban)}, Transaction ID: ${result.id}`,
      )
      setPage(0)
      await refreshSummary()
      await refreshHistory()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) clearPendingTransferKey()
      setFormError(getFriendlyError(error))
    } finally {
      setSubmitting(false)
    }
  }

  async function showTransfer(transferId: string) {
    setDetailError('')
    setSelectedTransfer(null)
    try {
      setSelectedTransfer(await getTransfer(accessToken, transferId))
    } catch (error) {
      setDetailError(getFriendlyError(error))
    }
  }

  if (!authReady) {
    return <main className="center-message">Checking secure session…</main>
  }

  if (!user?.access_token) {
    return (
      <main className="signin-page">
        <section className="signin-card">
          <h1>Money Transfer</h1>
          <p>Sign in to view your accounts and transfers.</p>
          {!isAuthenticationEnabled && (
            <p className="notice">
              Configure <code>OIDC_ISSUER_URI</code> and <code>OIDC_CLIENT_ID</code> in your
              <code> .env</code> file to sign in.
            </p>
          )}
          {authError && <p className="error-message" role="alert">{authError}</p>}
          {isDemoAuthEnabled ? (
            <form
              className="signin-form"
              onSubmit={(event) => {
                event.preventDefault()
                void handleSignIn({ customerId: demoCustomerId, password: demoPassword })
              }}
            >
              <label htmlFor="demo-customer-id">
                Customer ID
                <input
                  id="demo-customer-id"
                  autoComplete="username"
                  value={demoCustomerId}
                  onChange={(event) => setDemoCustomerId(event.target.value)}
                  required
                />
              </label>
              <label htmlFor="demo-password">
                Password
                <input
                  id="demo-password"
                  type="password"
                  autoComplete="current-password"
                  value={demoPassword}
                  onChange={(event) => setDemoPassword(event.target.value)}
                  required
                />
              </label>
              <button type="submit">Sign in</button>
            </form>
          ) : (
            <button type="button" onClick={() => void handleSignIn()} disabled={!isAuthenticationEnabled}>
              Sign in with OIDC
            </button>
          )}
        </section>
      </main>
    )
  }

  return (
    <main className="app">
      <header className="app-header">
        <h1>Money Transfer</h1>
        <div className="user-actions">
          <span>{customerName}</span>
          <button type="button" className="button-secondary" onClick={() => void handleSignOut()}>
            Sign out
          </button>
        </div>
      </header>

      <nav className="tabs" aria-label="Pages">
        <button
          type="button"
          className={view === 'accounts' ? 'tab-active' : ''}
          onClick={() => setView('accounts')}
        >
          Accounts
        </button>
        <button
          type="button"
          className={view === 'transfers' ? 'tab-active' : ''}
          onClick={() => setView('transfers')}
        >
          Transfers
        </button>
      </nav>

      {summaryError && (
        <div className="error-message" role="alert">
          {summaryError} <button type="button" onClick={() => void refreshSummary()}>Retry</button>
        </div>
      )}

      {view === 'accounts' ? (
        <>
          <section className="summary-grid" aria-label="Summary">
            <SummaryCard title="Toplam TRY bakiyesi" value={summaryLoading ? '—' : formatMoney(totalBalance)} />
            <SummaryCard
              title="Daily limit remaining"
              value={summaryLoading ? '—' : formatMoney(limits?.dailyRemaining ?? '0.00')}
            />
            <SummaryCard
              title="Monthly limit remaining"
              value={summaryLoading ? '—' : formatMoney(limits?.monthlyRemaining ?? '0.00')}
            />
          </section>

          <div className="dashboard-grid">
            <div className="main-column">
              <section className="panel">
                <h2>My accounts</h2>
                {summaryLoading ? (
                  <p className="muted">Loading accounts…</p>
                ) : accounts.length === 0 ? (
                  <p className="muted">No accounts to display.</p>
                ) : (
                  <div className="account-list">
                    {accounts.map((account) => (
                      <article className="account-row" key={account.id}>
                        <div>
                          <strong>{account.iban}</strong>
                          <span>{account.currency} · {account.status === 'ACTIVE' ? 'Active' : 'Inactive'}</span>
                        </div>
                        <strong>{formatMoney(account.balance, account.currency)}</strong>
                      </article>
                    ))}
                  </div>
                )}
              </section>

              <section className="panel">
                <h2>Monthly transfer summary</h2>
                {summaryLoading ? (
                  <p className="muted">Loading statistics…</p>
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr><th>Month</th><th>Transfer count</th><th>Total amount</th></tr>
                      </thead>
                      <tbody>
                        {statistics.map((item) => (
                          <tr key={item.month}>
                            <td>{formatMonth(item.month)}</td>
                            <td>{item.completedTransferCount}</td>
                            <td>{formatMoney(item.completedOutgoingAmount)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </section>
            </div>

            <section className="panel">
              <h2>New transfer</h2>
              <form className="transfer-form" onSubmit={(event) => void handleCreateTransfer(event)}>
                <label>
                  Source account
                  <select
                    value={form.sourceAccountId}
                    onChange={(event) => setForm({ ...form, sourceAccountId: event.target.value })}
                    disabled={activeAccounts.length === 0 || submitting}
                  >
                    {activeAccounts.length === 0 && <option value="">No active TRY account</option>}
                    {activeAccounts.map((account) => (
                      <option value={account.id} key={account.id}>{maskIban(account.iban)}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Recipient IBAN
                  <input
                    autoComplete="off"
                    maxLength={42}
                    value={formatIbanBlocks(form.receiverIban)}
                    onChange={(event) => setForm({ ...form, receiverIban: event.target.value.replace(/\s/g, '').toUpperCase() })}
                    placeholder="TR00 0000 0000 ..."
                    disabled={submitting}
                  />
                </label>
                <label>
                  Amount (TRY)
                  <input
                    inputMode="decimal"
                    value={form.amount}
                    onChange={(event) => setForm({ ...form, amount: formatAmountInput(event.target.value) })}
                    placeholder="0.00"
                    disabled={submitting}
                  />
                  {limits && <small>Maximum per transfer: {formatMoney(limits.perTransferMaximum)}</small>}
                </label>
                <label>
                  Description <span className="muted">(optional)</span>
                  <input
                    maxLength={500}
                    value={form.description}
                    onChange={(event) => setForm({ ...form, description: event.target.value })}
                    placeholder="Transfer description"
                    disabled={submitting}
                  />
                </label>
                {formError && <p className="error-message" role="alert">{formError}</p>}
                {formSuccess && <p className="success-message" role="status">{formSuccess}</p>}
                <button type="submit" disabled={submitting || activeAccounts.length === 0}>
                  {submitting ? 'Sending…' : 'Send transfer'}
                </button>
              </form>
            </section>
          </div>

          <section className="panel">
            <div className="section-heading">
              <h2>Recent transfers</h2>
              <button type="button" className="text-button" onClick={() => setView('transfers')}>View all</button>
            </div>
            <TransferRows
              transfers={transfers.slice(0, 5)}
              loading={historyLoading}
              onSelect={(id) => void showTransfer(id)}
            />
          </section>
        </>
      ) : (
        <section className="panel">
          <div className="history-heading">
            <h2>Transfer history</h2>
            <div className="date-filters">
              <label>Start date <input type="date" value={startDate} onChange={(event) => {
                setStartDate(event.target.value)
                setPage(0)
              }} /></label>
              <label>End date <input type="date" value={endDate} onChange={(event) => {
                setEndDate(event.target.value)
                setPage(0)
              }} /></label>
              {(startDate || endDate) && (
                <button type="button" className="text-button" onClick={() => {
                  setStartDate('')
                  setEndDate('')
                  setPage(0)
                }}>Clear</button>
              )}
            </div>
          </div>
          <div className="history-direction-filter" role="group" aria-label="Transfer type">
            {([
              ['ALL', 'All'],
              ['OUTGOING', 'Outgoing'],
              ['INCOMING', 'Incoming'],
            ] as const).map(([direction, label]) => (
              <button
                type="button"
                key={direction}
                className={`button-secondary${historyDirection === direction ? ' history-filter-active' : ''}`}
                aria-pressed={historyDirection === direction}
                onClick={() => {
                  setHistoryDirection(direction)
                  setPage(0)
                }}
              >
                {label}
              </button>
            ))}
          </div>
          {historyError && <p className="error-message" role="alert">{historyError}</p>}
          <TransferRows transfers={transfers} loading={historyLoading} onSelect={(id) => void showTransfer(id)} />
          <div className="pagination">
            <span>
              {pageMeta.totalElements} {pageMeta.totalElements === 1 ? 'transfer' : 'transfers'}
            </span>
            <button type="button" className="button-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </button>
            <span>{pageMeta.totalPages === 0 ? 0 : page + 1} / {pageMeta.totalPages}</span>
            <button
              type="button"
              className="button-secondary"
              disabled={page + 1 >= pageMeta.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Next
            </button>
          </div>
        </section>
      )}

      {(selectedTransfer || detailError) && (
        <div className="dialog-backdrop" onMouseDown={(event) => {
          if (event.target === event.currentTarget) {
            setSelectedTransfer(null)
            setDetailError('')
          }
        }}>
          <section className="dialog" role="dialog" aria-modal="true" aria-labelledby="transfer-detail-title">
            <div className="section-heading">
              <h2 id="transfer-detail-title">Transfer details</h2>
              <button type="button" className="button-secondary" onClick={() => {
                setSelectedTransfer(null)
                setDetailError('')
              }}>Close</button>
            </div>
            {detailError ? <p className="error-message">{detailError}</p> : selectedTransfer && (
              <dl className="detail-list">
                <dt>Amount</dt><dd>{formatMoney(selectedTransfer.amount, selectedTransfer.currency)}</dd>
                <dt>Direction</dt><dd>{selectedTransfer.direction === 'INCOMING' ? 'Incoming' : 'Outgoing'}</dd>
                <dt>Sender IBAN</dt><dd>{maskIban(selectedTransfer.sourceIban)}</dd>
                <dt>Recipient IBAN</dt><dd>{maskIban(selectedTransfer.receiverIban)}</dd>
                <dt>Description</dt><dd>{selectedTransfer.description || '—'}</dd>
                <dt>Date</dt><dd>{formatDate(selectedTransfer.createdAt)}</dd>
                <dt>Status</dt><dd>Completed</dd>
                <dt>Transaction ID</dt><dd>{selectedTransfer.id}</dd>
              </dl>
            )}
          </section>
        </div>
      )}
    </main>
  )
}

function SummaryCard({ title, value }: { title: string; value: string }) {
  return (
    <section className="summary-card">
      <span>{title}</span>
      <strong>{value}</strong>
    </section>
  )
}

function TransferRows({
  transfers,
  loading,
  onSelect,
}: {
  transfers: Transfer[]
  loading: boolean
  onSelect: (id: string) => void
}) {
  if (loading) return <p className="muted">Loading transfers…</p>
  if (transfers.length === 0) return <p className="muted">No transfers found.</p>

  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr><th>Direction</th><th>Counterparty</th><th>Description</th><th>Date</th><th>Amount</th><th>Details</th></tr>
        </thead>
        <tbody>
          {transfers.map((transfer) => (
            <tr key={transfer.id}>
              <td>{transfer.direction === 'INCOMING' ? 'Incoming' : 'Outgoing'}</td>
              <td>
                <span className="muted" style={{ fontSize: '0.85em', marginRight: '4px' }}>
                  {transfer.direction === 'INCOMING' ? 'From: ' : 'To: '}
                </span>
                {maskIban(
                  transfer.direction === 'INCOMING' ? transfer.sourceIban : transfer.receiverIban,
                )}
              </td>
              <td>{transfer.description || '—'}</td>
              <td>{formatDate(transfer.createdAt)}</td>
              <td>
                {transfer.direction === 'INCOMING' ? '+' : '−'}
                {formatMoney(transfer.amount, transfer.currency)}
              </td>
              <td>
                <button type="button" className="text-button" onClick={() => onSelect(transfer.id)}>
                  Details
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function toCents(value: string): bigint {
  const [whole = '0', fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0').slice(0, 2) || '0')
}

function fromCents(value: bigint): string {
  return `${value / 100n}.${(value % 100n).toString().padStart(2, '0')}`
}

function formatMonth(month: string): string {
  const [year, value] = month.split('-').map(Number)
  return new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric' }).format(new Date(year, value - 1, 1))
}

function getFriendlyError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Your session is invalid or has expired. Please sign in again.'
    if (error.status === 403) return 'Your customer account is not registered.'
    if (error.status === 404) return 'Transfer not found.'
    if (error.status === 409) return 'This idempotency key was already used for a different transfer.'
    if (error.status === 422) {
      if (error.code === 'INSUFFICIENT_FUNDS') return 'The source account has insufficient funds.'
      if (error.code.includes('LIMIT_EXCEEDED')) return 'The transfer limit does not allow this transaction.'
      if (error.code === 'ACCOUNT_NOT_AVAILABLE') return 'The selected account or recipient IBAN is not available.'
      if (error.code === 'SAME_ACCOUNT') return 'Source and destination accounts must be different.'
      return error.message || 'The transfer could not be completed due to a business rule.'
    }
    if (error.status === 400) return 'Please check the information you entered.'
    return 'The request could not be completed. Please try again.'
  }
  if (error instanceof TypeError) return 'Could not connect to the server. Check your connection.'
  if (error instanceof Error && error.message.includes('cryptography')) {
    return 'Could not create a secure transfer key. Try using HTTPS or localhost.'
  }
  return 'An unexpected error occurred.'
}
