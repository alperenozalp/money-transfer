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
import { formatDate, formatMoney, maskIban } from './format'
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
    return typeof name === 'string' && name.trim() ? name : 'Hesabım'
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
        if (active) setAuthError('Giriş tamamlanamadı. Lütfen yeniden deneyin.')
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
      setForm((current) => ({
        ...current,
        sourceAccountId: current.sourceAccountId || accountList.find(
          (account) => account.status === 'ACTIVE' && account.currency === 'TRY',
        )?.id || '',
      }))
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
        : 'Kimlik sağlayıcısına bağlanılamadı. OIDC ayarlarınızı kontrol edin.')
    }
  }

  async function handleSignOut() {
    try {
      await signOut()
      setUser(null)
    } catch {
      await clearSessionUser()
      setUser(null)
      setAuthError('Oturum kapatılamadı. Lütfen tarayıcı oturumunu kapatıp yeniden deneyin.')
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
      const result = await createTransfer(accessToken, idempotencyKey, {
        sourceAccountId: form.sourceAccountId,
        receiverIban: form.receiverIban.trim().toUpperCase(),
        amount: form.amount,
        description: form.description.trim() || undefined,
      })
      clearPendingTransferKey()
      setForm(EMPTY_FORM)
      setFormSuccess(`Transfer tamamlandı. İşlem numarası: ${result.id}`)
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
    return <main className="center-message">Güvenli oturum kontrol ediliyor…</main>
  }

  if (!user?.access_token) {
    return (
      <main className="signin-page">
        <section className="signin-card">
          <h1>Money Transfer</h1>
          <p>Hesap ve transferlerinizi görüntülemek için giriş yapın.</p>
          {!isAuthenticationEnabled && (
            <p className="notice">
              Giriş için <code>OIDC_ISSUER_URI</code> ve <code>OIDC_CLIENT_ID</code> değerlerini
              <code> .env</code> dosyasında yapılandırın.
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
                Müşteri ID
                <input
                  id="demo-customer-id"
                  autoComplete="username"
                  value={demoCustomerId}
                  onChange={(event) => setDemoCustomerId(event.target.value)}
                  required
                />
              </label>
              <label htmlFor="demo-password">
                Şifre
                <input
                  id="demo-password"
                  type="password"
                  autoComplete="current-password"
                  value={demoPassword}
                  onChange={(event) => setDemoPassword(event.target.value)}
                  required
                />
              </label>
              <button type="submit">Giriş yap</button>
            </form>
          ) : (
            <button type="button" onClick={() => void handleSignIn()} disabled={!isAuthenticationEnabled}>
              OIDC ile giriş yap
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
            Çıkış
          </button>
        </div>
      </header>

      <nav className="tabs" aria-label="Sayfalar">
        <button
          type="button"
          className={view === 'accounts' ? 'tab-active' : ''}
          onClick={() => setView('accounts')}
        >
          Hesaplar
        </button>
        <button
          type="button"
          className={view === 'transfers' ? 'tab-active' : ''}
          onClick={() => setView('transfers')}
        >
          Transferler
        </button>
      </nav>

      {summaryError && (
        <div className="error-message" role="alert">
          {summaryError} <button type="button" onClick={() => void refreshSummary()}>Yeniden dene</button>
        </div>
      )}

      {view === 'accounts' ? (
        <>
          <section className="summary-grid" aria-label="Özet">
            <SummaryCard title="Toplam TRY bakiyesi" value={summaryLoading ? '—' : formatMoney(totalBalance)} />
            <SummaryCard
              title="Günlük kalan limit"
              value={summaryLoading ? '—' : formatMoney(limits?.dailyRemaining ?? '0.00')}
            />
            <SummaryCard
              title="Aylık kalan limit"
              value={summaryLoading ? '—' : formatMoney(limits?.monthlyRemaining ?? '0.00')}
            />
          </section>

          <div className="dashboard-grid">
            <div className="main-column">
              <section className="panel">
                <h2>Hesaplarım</h2>
                {summaryLoading ? (
                  <p className="muted">Hesaplar yükleniyor…</p>
                ) : accounts.length === 0 ? (
                  <p className="muted">Görüntülenecek hesap bulunamadı.</p>
                ) : (
                  <div className="account-list">
                    {accounts.map((account) => (
                      <article className="account-row" key={account.id}>
                        <div>
                          <strong>{account.iban}</strong>
                          <span>{account.currency} · {account.status === 'ACTIVE' ? 'Aktif' : 'Pasif'}</span>
                        </div>
                        <strong>{formatMoney(account.balance, account.currency)}</strong>
                      </article>
                    ))}
                  </div>
                )}
              </section>

              <section className="panel">
                <h2>Aylık transfer özeti</h2>
                {summaryLoading ? (
                  <p className="muted">İstatistikler yükleniyor…</p>
                ) : (
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr><th>Ay</th><th>Transfer sayısı</th><th>Toplam tutar</th></tr>
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
              <h2>Yeni transfer</h2>
              <form className="transfer-form" onSubmit={(event) => void handleCreateTransfer(event)}>
                <label>
                  Kaynak hesap
                  <select
                    value={form.sourceAccountId}
                    onChange={(event) => setForm({ ...form, sourceAccountId: event.target.value })}
                    disabled={activeAccounts.length === 0 || submitting}
                  >
                    {activeAccounts.length === 0 && <option value="">Aktif TRY hesabı yok</option>}
                    {activeAccounts.map((account) => (
                      <option value={account.id} key={account.id}>{maskIban(account.iban)}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Alıcı IBAN
                  <input
                    autoComplete="off"
                    maxLength={34}
                    value={form.receiverIban}
                    onChange={(event) => setForm({ ...form, receiverIban: event.target.value.replace(/\s/g, '').toUpperCase() })}
                    placeholder="TR…"
                    disabled={submitting}
                  />
                </label>
                <label>
                  Tutar (TRY)
                  <input
                    inputMode="decimal"
                    value={form.amount}
                    onChange={(event) => setForm({ ...form, amount: event.target.value.replace(',', '.') })}
                    placeholder="0.00"
                    disabled={submitting}
                  />
                  {limits && <small>İşlem başına en fazla {formatMoney(limits.perTransferMaximum)}</small>}
                </label>
                <label>
                  Açıklama <span className="muted">(isteğe bağlı)</span>
                  <input
                    maxLength={500}
                    value={form.description}
                    onChange={(event) => setForm({ ...form, description: event.target.value })}
                    placeholder="Transfer açıklaması"
                    disabled={submitting}
                  />
                </label>
                {formError && <p className="error-message" role="alert">{formError}</p>}
                {formSuccess && <p className="success-message" role="status">{formSuccess}</p>}
                <button type="submit" disabled={submitting || activeAccounts.length === 0}>
                  {submitting ? 'Gönderiliyor…' : 'Transfer gönder'}
                </button>
              </form>
            </section>
          </div>

          <section className="panel">
            <div className="section-heading">
              <h2>Son transferler</h2>
              <button type="button" className="text-button" onClick={() => setView('transfers')}>Tümünü gör</button>
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
            <h2>Transfer geçmişi</h2>
            <div className="date-filters">
              <label>Başlangıç <input type="date" value={startDate} onChange={(event) => {
                setStartDate(event.target.value)
                setPage(0)
              }} /></label>
              <label>Bitiş <input type="date" value={endDate} onChange={(event) => {
                setEndDate(event.target.value)
                setPage(0)
              }} /></label>
              {(startDate || endDate) && (
                <button type="button" className="text-button" onClick={() => {
                  setStartDate('')
                  setEndDate('')
                  setPage(0)
                }}>Temizle</button>
              )}
            </div>
          </div>
          <div className="history-direction-filter" role="group" aria-label="Transfer türü">
            {([
              ['ALL', 'Tümü'],
              ['OUTGOING', 'Gönderilen'],
              ['INCOMING', 'Gelen'],
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
            <span>{pageMeta.totalElements} transfer</span>
            <button type="button" className="button-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Önceki
            </button>
            <span>{pageMeta.totalPages === 0 ? 0 : page + 1} / {pageMeta.totalPages}</span>
            <button
              type="button"
              className="button-secondary"
              disabled={page + 1 >= pageMeta.totalPages}
              onClick={() => setPage(page + 1)}
            >
              Sonraki
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
              <h2 id="transfer-detail-title">Transfer detayı</h2>
              <button type="button" className="button-secondary" onClick={() => {
                setSelectedTransfer(null)
                setDetailError('')
              }}>Kapat</button>
            </div>
            {detailError ? <p className="error-message">{detailError}</p> : selectedTransfer && (
              <dl className="detail-list">
                <dt>Tutar</dt><dd>{formatMoney(selectedTransfer.amount, selectedTransfer.currency)}</dd>
                <dt>Yön</dt><dd>{selectedTransfer.direction === 'INCOMING' ? 'Gelen' : 'Gönderilen'}</dd>
                <dt>Gönderen IBAN</dt><dd>{maskIban(selectedTransfer.sourceIban)}</dd>
                <dt>Alıcı IBAN</dt><dd>{maskIban(selectedTransfer.receiverIban)}</dd>
                <dt>Açıklama</dt><dd>{selectedTransfer.description || '—'}</dd>
                <dt>Tarih</dt><dd>{formatDate(selectedTransfer.createdAt)}</dd>
                <dt>Durum</dt><dd>Tamamlandı</dd>
                <dt>İşlem numarası</dt><dd>{selectedTransfer.id}</dd>
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
  if (loading) return <p className="muted">Transferler yükleniyor…</p>
  if (transfers.length === 0) return <p className="muted">Transfer bulunamadı.</p>

  return (
    <div className="table-scroll">
      <table>
        <thead>
          <tr><th>Yön</th><th>Karşı taraf</th><th>Açıklama</th><th>Tarih</th><th>Tutar</th><th>Detay</th></tr>
        </thead>
        <tbody>
          {transfers.map((transfer) => (
            <tr key={transfer.id}>
              <td>{transfer.direction === 'INCOMING' ? 'Gelen' : 'Gönderilen'}</td>
              <td>{maskIban(
                transfer.direction === 'INCOMING' ? transfer.sourceIban : transfer.receiverIban,
              )}</td>
              <td>{transfer.description || '—'}</td>
              <td>{formatDate(transfer.createdAt)}</td>
              <td>
                {transfer.direction === 'INCOMING' ? '+' : '−'}
                {formatMoney(transfer.amount, transfer.currency)}
              </td>
              <td>
                <button type="button" className="text-button" onClick={() => onSelect(transfer.id)}>
                  Detay
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
  return new Intl.DateTimeFormat('tr-TR', { month: 'long', year: 'numeric' }).format(new Date(year, value - 1, 1))
}

function getFriendlyError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Oturum geçersiz veya süresi dolmuş. Yeniden giriş yapın.'
    if (error.status === 403) return 'Müşteri hesabınız sisteme tanımlı değil.'
    if (error.status === 404) return 'Transfer bulunamadı.'
    if (error.status === 409) return 'Bu istek anahtarı farklı bir transfer için kullanılmış.'
    if (error.status === 422) {
      if (error.code === 'INSUFFICIENT_FUNDS') return 'Kaynak hesapta yeterli bakiye yok.'
      if (error.code.includes('LIMIT_EXCEEDED')) return 'Transfer limiti bu işlem için yeterli değil.'
      return 'Transfer iş kuralı nedeniyle gerçekleştirilemedi.'
    }
    if (error.status === 400) return 'Girdiğiniz bilgileri kontrol edin.'
    return 'İstek tamamlanamadı. Yeniden deneyin.'
  }
  if (error instanceof TypeError) return 'Sunucuya bağlanılamadı. Bağlantınızı kontrol edin.'
  if (error instanceof Error && error.message.includes('cryptography')) {
    return 'Güvenli transfer anahtarı oluşturulamadı. HTTPS veya localhost üzerinden deneyin.'
  }
  return 'Beklenmeyen bir hata oluştu.'
}
