import { useEffect, useState } from 'react';
import api from '../api/client';

export default function Transfer() {
  const [accounts, setAccounts] = useState([]);
  const [form, setForm] = useState({ fromAccountNumber: '', toAccountNumber: '', amount: '', description: '' });
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    api.get('/accounts').then(({ data }) => {
      setAccounts(data);
      if (data.length > 0) setForm((f) => ({ ...f, fromAccountNumber: data[0].accountNumber }));
    });
  }, []);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setSuccess('');
    setSubmitting(true);
    try {
      const { data } = await api.post('/transactions/transfer', {
        fromAccountNumber: form.fromAccountNumber,
        toAccountNumber: form.toAccountNumber,
        amount: Number(form.amount),
        description: form.description,
      });
      setSuccess(`Transfer completed — reference ${data.reference.slice(0, 8)}`);
      setForm({ ...form, toAccountNumber: '', amount: '', description: '' });
    } catch (err) {
      setError(err.response?.data?.message || 'This transfer could not be completed.');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div>
      <h1>Transfer funds</h1>
      <p className="helper-text">Move money between your own accounts, or send to someone else's account number.</p>

      <div className="panel" style={{ marginTop: 18, maxWidth: 480 }}>
        <form onSubmit={handleSubmit}>
          <div className="field">
            <label>From account</label>
            <select
              value={form.fromAccountNumber}
              onChange={(e) => setForm({ ...form, fromAccountNumber: e.target.value })}
              required
            >
              <option value="" disabled>Select an account</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.accountNumber}>
                  {a.accountName} — {a.accountNumber} (${Number(a.balance).toFixed(2)})
                </option>
              ))}
            </select>
          </div>

          <div className="field">
            <label>To account number</label>
            <input
              value={form.toAccountNumber}
              onChange={(e) => setForm({ ...form, toAccountNumber: e.target.value })}
              placeholder="10-digit account number"
              required
            />
          </div>

          <div className="field">
            <label>Amount</label>
            <input
              type="number" min="0.01" step="0.01" required
              value={form.amount}
              onChange={(e) => setForm({ ...form, amount: e.target.value })}
            />
          </div>

          <div className="field">
            <label>Description (optional)</label>
            <input
              value={form.description}
              onChange={(e) => setForm({ ...form, description: e.target.value })}
              placeholder="e.g. Rent, groceries, gift"
            />
          </div>

          {error && <div className="error-text">{error}</div>}
          {success && <div style={{ color: 'var(--emerald-deep)', fontSize: '0.85rem', marginTop: 6 }}>{success}</div>}

          <button type="submit" className="btn btn-primary" style={{ marginTop: 8 }} disabled={submitting || accounts.length === 0}>
            {submitting ? 'Sending…' : 'Send transfer'}
          </button>
        </form>
      </div>
    </div>
  );
}
