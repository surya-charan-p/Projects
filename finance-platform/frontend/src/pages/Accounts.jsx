import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import api from '../api/client';

const ACCOUNT_TYPES = ['CHECKING', 'SAVINGS', 'CREDIT'];

export default function Accounts() {
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showForm, setShowForm] = useState(false);
  const [form, setForm] = useState({ accountName: '', accountType: 'CHECKING', initialBalance: '' });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const loadAccounts = async () => {
    setLoading(true);
    const { data } = await api.get('/accounts');
    setAccounts(data);
    setLoading(false);
  };

  useEffect(() => {
    loadAccounts();
  }, []);

  const handleCreate = async (e) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await api.post('/accounts', {
        accountName: form.accountName,
        accountType: form.accountType,
        initialBalance: form.initialBalance ? Number(form.initialBalance) : 0,
      });
      setForm({ accountName: '', accountType: 'CHECKING', initialBalance: '' });
      setShowForm(false);
      await loadAccounts();
    } catch (err) {
      setError(err.response?.data?.message || 'Could not open the account.');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) return <p className="helper-text">Loading accounts…</p>;

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <h1>Accounts</h1>
          <p className="helper-text">Every account you hold on the platform.</p>
        </div>
        <button className="btn btn-primary" onClick={() => setShowForm((v) => !v)}>
          {showForm ? 'Cancel' : 'Open new account'}
        </button>
      </div>

      {showForm && (
        <div className="panel" style={{ marginTop: 16, maxWidth: 420 }}>
          <h3>Open a new account</h3>
          <form onSubmit={handleCreate}>
            <div className="field">
              <label>Account name</label>
              <input
                value={form.accountName}
                onChange={(e) => setForm({ ...form, accountName: e.target.value })}
                placeholder="e.g. Everyday Checking"
                required
              />
            </div>
            <div className="field">
              <label>Account type</label>
              <select
                value={form.accountType}
                onChange={(e) => setForm({ ...form, accountType: e.target.value })}
              >
                {ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
            </div>
            <div className="field">
              <label>Opening deposit (optional)</label>
              <input
                type="number"
                min="0"
                step="0.01"
                value={form.initialBalance}
                onChange={(e) => setForm({ ...form, initialBalance: e.target.value })}
                placeholder="0.00"
              />
            </div>
            {error && <div className="error-text">{error}</div>}
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Opening…' : 'Open account'}
            </button>
          </form>
        </div>
      )}

      {accounts.length === 0 ? (
        <div className="panel empty-state" style={{ marginTop: 18 }}>
          <p>No accounts yet — open your first one above.</p>
        </div>
      ) : (
        <div style={{ marginTop: 18, display: 'flex', flexDirection: 'column', gap: 14 }}>
          {accounts.map((acc) => (
            <Link key={acc.id} to={`/accounts/${acc.id}`} className="panel" style={{ display: 'block', color: 'inherit' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <div>
                  <h3 style={{ marginBottom: 2 }}>{acc.accountName}</h3>
                  <div className="account-number">{acc.accountNumber} · {acc.accountType}</div>
                </div>
                <div style={{ textAlign: 'right' }}>
                  <div className="balance-figure" style={{ fontSize: '1.5rem' }}>
                    ${Number(acc.balance).toLocaleString(undefined, { minimumFractionDigits: 2 })}
                  </div>
                  <span className={`badge badge-${acc.status.toLowerCase()}`}>{acc.status}</span>
                </div>
              </div>
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
