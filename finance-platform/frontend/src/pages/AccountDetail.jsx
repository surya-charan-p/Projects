import { useEffect, useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import api from '../api/client';

const TYPES = ['', 'TRANSFER', 'DEPOSIT', 'WITHDRAWAL'];
const STATUSES = ['', 'PENDING', 'COMPLETED', 'FAILED'];

export default function AccountDetail() {
  const { id } = useParams();
  const navigate = useNavigate();

  const [account, setAccount] = useState(null);
  const [page, setPage] = useState({ content: [], totalPages: 0, number: 0 });
  const [pageIndex, setPageIndex] = useState(0);
  const [filters, setFilters] = useState({ type: '', status: '', minAmount: '', maxAmount: '' });

  const [fundsForm, setFundsForm] = useState({ mode: 'deposit', amount: '', description: '' });
  const [fundsError, setFundsError] = useState('');
  const [fundsSubmitting, setFundsSubmitting] = useState(false);
  const [closeError, setCloseError] = useState('');

  const loadAccount = useCallback(async () => {
    const { data } = await api.get(`/accounts/${id}`);
    setAccount(data);
  }, [id]);

  const loadHistory = useCallback(async () => {
    const params = { page: pageIndex, size: 10 };
    if (filters.type) params.type = filters.type;
    if (filters.status) params.status = filters.status;
    if (filters.minAmount) params.minAmount = filters.minAmount;
    if (filters.maxAmount) params.maxAmount = filters.maxAmount;

    const { data } = await api.get(`/transactions/account/${id}`, { params });
    setPage(data);
  }, [id, pageIndex, filters]);

  useEffect(() => { loadAccount(); }, [loadAccount]);
  useEffect(() => { loadHistory(); }, [loadHistory]);

  const handleFunds = async (e) => {
    e.preventDefault();
    setFundsError('');
    setFundsSubmitting(true);
    try {
      const endpoint = fundsForm.mode === 'deposit' ? '/transactions/deposit' : '/transactions/withdraw';
      await api.post(endpoint, {
        accountNumber: account.accountNumber,
        amount: Number(fundsForm.amount),
        description: fundsForm.description,
      });
      setFundsForm({ mode: fundsForm.mode, amount: '', description: '' });
      await Promise.all([loadAccount(), loadHistory()]);
    } catch (err) {
      setFundsError(err.response?.data?.message || 'That transaction could not be completed.');
    } finally {
      setFundsSubmitting(false);
    }
  };

  const handleClose = async () => {
    setCloseError('');
    if (!window.confirm('Close this account? This cannot be undone.')) return;
    try {
      await api.delete(`/accounts/${id}`);
      navigate('/accounts');
    } catch (err) {
      setCloseError(err.response?.data?.message || 'Could not close this account.');
    }
  };

  if (!account) return <p className="helper-text">Loading account…</p>;

  return (
    <div>
      <h1>{account.accountName}</h1>
      <div className="account-number" style={{ marginBottom: 18 }}>
        {account.accountNumber} · {account.accountType} · <span className={`badge badge-${account.status.toLowerCase()}`}>{account.status}</span>
      </div>

      <div className="grid-2">
        <div className="panel">
          <div className="helper-text">Current balance</div>
          <div className="balance-figure">${Number(account.balance).toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>

          {account.status === 'ACTIVE' && (
            <button className="btn btn-danger" style={{ marginTop: 16 }} onClick={handleClose}>
              Close account
            </button>
          )}
          {closeError && <div className="error-text">{closeError}</div>}
        </div>

        <div className="panel">
          <h3>Deposit or withdraw</h3>
          <form onSubmit={handleFunds}>
            <div className="field">
              <label>Action</label>
              <select value={fundsForm.mode} onChange={(e) => setFundsForm({ ...fundsForm, mode: e.target.value })}>
                <option value="deposit">Deposit</option>
                <option value="withdraw">Withdraw</option>
              </select>
            </div>
            <div className="field">
              <label>Amount</label>
              <input
                type="number" min="0.01" step="0.01" required
                value={fundsForm.amount}
                onChange={(e) => setFundsForm({ ...fundsForm, amount: e.target.value })}
              />
            </div>
            <div className="field">
              <label>Description (optional)</label>
              <input
                value={fundsForm.description}
                onChange={(e) => setFundsForm({ ...fundsForm, description: e.target.value })}
              />
            </div>
            {fundsError && <div className="error-text">{fundsError}</div>}
            <button type="submit" className="btn btn-primary" disabled={fundsSubmitting}>
              {fundsSubmitting ? 'Processing…' : (fundsForm.mode === 'deposit' ? 'Deposit funds' : 'Withdraw funds')}
            </button>
          </form>
        </div>
      </div>

      <div className="panel" style={{ marginTop: 18 }}>
        <h3>Transaction history</h3>

        <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
          <div className="field" style={{ marginBottom: 0, minWidth: 140 }}>
            <label>Type</label>
            <select value={filters.type} onChange={(e) => { setFilters({ ...filters, type: e.target.value }); setPageIndex(0); }}>
              {TYPES.map((t) => <option key={t} value={t}>{t || 'All'}</option>)}
            </select>
          </div>
          <div className="field" style={{ marginBottom: 0, minWidth: 140 }}>
            <label>Status</label>
            <select value={filters.status} onChange={(e) => { setFilters({ ...filters, status: e.target.value }); setPageIndex(0); }}>
              {STATUSES.map((s) => <option key={s} value={s}>{s || 'All'}</option>)}
            </select>
          </div>
          <div className="field" style={{ marginBottom: 0, minWidth: 120 }}>
            <label>Min amount</label>
            <input type="number" value={filters.minAmount} onChange={(e) => { setFilters({ ...filters, minAmount: e.target.value }); setPageIndex(0); }} />
          </div>
          <div className="field" style={{ marginBottom: 0, minWidth: 120 }}>
            <label>Max amount</label>
            <input type="number" value={filters.maxAmount} onChange={(e) => { setFilters({ ...filters, maxAmount: e.target.value }); setPageIndex(0); }} />
          </div>
        </div>

        {page.content.length === 0 ? (
          <p className="helper-text">No transactions match these filters.</p>
        ) : (
          <>
            <table className="ledger">
              <thead>
                <tr>
                  <th>Date</th>
                  <th>Type</th>
                  <th>From</th>
                  <th>To</th>
                  <th>Amount</th>
                  <th>Status</th>
                  <th>Note</th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((tx) => {
                  const isOutflow = tx.fromAccountNumber === account.accountNumber;
                  return (
                    <tr key={tx.id}>
                      <td>{new Date(tx.createdAt).toLocaleString()}</td>
                      <td>{tx.type}</td>
                      <td className="account-number">{tx.fromAccountNumber || '—'}</td>
                      <td className="account-number">{tx.toAccountNumber || '—'}</td>
                      <td className={isOutflow ? 'amount-negative' : 'amount-positive'}>
                        {isOutflow ? '-' : '+'}${Number(tx.amount).toFixed(2)}
                      </td>
                      <td><span className={`badge badge-${tx.status.toLowerCase()}`}>{tx.status}</span></td>
                      <td className="helper-text">{tx.description || tx.failureReason || '—'}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            <div style={{ display: 'flex', gap: 8, marginTop: 16, alignItems: 'center' }}>
              <button className="btn btn-secondary" disabled={pageIndex === 0} onClick={() => setPageIndex((p) => p - 1)}>
                Previous
              </button>
              <span className="helper-text">Page {page.number + 1} of {Math.max(page.totalPages, 1)}</span>
              <button className="btn btn-secondary" disabled={pageIndex + 1 >= page.totalPages} onClick={() => setPageIndex((p) => p + 1)}>
                Next
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
