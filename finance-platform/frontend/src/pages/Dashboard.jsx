import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from 'recharts';
import api from '../api/client';

export default function Dashboard() {
  const [accounts, setAccounts] = useState([]);
  const [recent, setRecent] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const { data: accts } = await api.get('/accounts');
        if (cancelled) return;
        setAccounts(accts);

        // Pull a handful of recent transactions from each account and merge
        const historyResults = await Promise.all(
          accts.map((a) =>
            api.get(`/transactions/account/${a.id}`, { params: { size: 5 } })
              .then((res) => res.data.content)
              .catch(() => [])
          )
        );
        if (cancelled) return;
        const merged = historyResults.flat()
          .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
          .slice(0, 8);
        setRecent(merged);
      } catch (err) {
        setError('Could not load your dashboard right now.');
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    load();
    return () => { cancelled = true; };
  }, []);

  const totalBalance = accounts.reduce((sum, a) => sum + Number(a.balance), 0);
  const chartData = accounts.map((a) => ({ name: a.accountName, balance: Number(a.balance) }));

  if (loading) return <p className="helper-text">Loading dashboard…</p>;
  if (error) return <p className="error-text">{error}</p>;

  return (
    <div>
      <h1>Dashboard</h1>
      <p className="helper-text">An overview of everything currently on the platform.</p>

      <div className="panel" style={{ marginTop: 20 }}>
        <div className="helper-text">Total balance across all accounts</div>
        <div className="balance-figure">${totalBalance.toLocaleString(undefined, { minimumFractionDigits: 2 })}</div>
      </div>

      {accounts.length === 0 ? (
        <div className="panel empty-state" style={{ marginTop: 18 }}>
          <p>You don't have any accounts yet.</p>
          <Link to="/accounts" className="btn btn-primary">Open your first account</Link>
        </div>
      ) : (
        <div className="grid-2" style={{ marginTop: 18 }}>
          <div className="panel">
            <h3>Balance by account</h3>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={chartData}>
                <CartesianGrid strokeDasharray="3 3" stroke="#D6D9E0" />
                <XAxis dataKey="name" tick={{ fontSize: 12 }} />
                <YAxis tick={{ fontSize: 12 }} />
                <Tooltip formatter={(v) => `$${Number(v).toFixed(2)}`} />
                <Bar dataKey="balance" fill="#1F6F54" radius={[3, 3, 0, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>

          <div className="panel">
            <h3>Recent activity</h3>
            {recent.length === 0 ? (
              <p className="helper-text">No transactions yet.</p>
            ) : (
              <table className="ledger">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Type</th>
                    <th>Amount</th>
                    <th>Status</th>
                  </tr>
                </thead>
                <tbody>
                  {recent.map((tx) => (
                    <tr key={tx.id}>
                      <td>{new Date(tx.createdAt).toLocaleDateString()}</td>
                      <td>{tx.type}</td>
                      <td>${Number(tx.amount).toFixed(2)}</td>
                      <td><span className={`badge badge-${tx.status.toLowerCase()}`}>{tx.status}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
