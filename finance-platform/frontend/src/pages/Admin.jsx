import { Fragment, useEffect, useState, useCallback } from 'react';
import api from '../api/client';

const ACCOUNT_STATUSES = ['ACTIVE', 'FROZEN', 'CLOSED'];

export default function Admin() {
  const [users, setUsers] = useState({ content: [], totalPages: 0, number: 0 });
  const [pageIndex, setPageIndex] = useState(0);
  const [expandedUser, setExpandedUser] = useState(null);
  const [userAccounts, setUserAccounts] = useState([]);
  const [error, setError] = useState('');

  const loadUsers = useCallback(async () => {
    const { data } = await api.get('/admin/users', { params: { page: pageIndex, size: 10 } });
    setUsers(data);
  }, [pageIndex]);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  const toggleExpand = async (userId) => {
    if (expandedUser === userId) {
      setExpandedUser(null);
      return;
    }
    const { data } = await api.get(`/admin/users/${userId}/accounts`);
    setUserAccounts(data);
    setExpandedUser(userId);
  };

  const setUserEnabled = async (userId, enabled) => {
    setError('');
    try {
      await api.patch(`/admin/users/${userId}/status`, null, { params: { enabled } });
      await loadUsers();
    } catch (err) {
      setError(err.response?.data?.message || 'Could not update this user.');
    }
  };

  const setAccountStatus = async (accountId, status) => {
    setError('');
    try {
      await api.patch(`/admin/accounts/${accountId}/status`, null, { params: { status } });
      const { data } = await api.get(`/admin/users/${expandedUser}/accounts`);
      setUserAccounts(data);
    } catch (err) {
      setError(err.response?.data?.message || 'Could not update this account.');
    }
  };

  return (
    <div>
      <h1>Admin</h1>
      <p className="helper-text">Manage platform users and their accounts.</p>

      {error && <div className="error-text" style={{ marginTop: 10 }}>{error}</div>}

      <div className="panel" style={{ marginTop: 18 }}>
        <table className="ledger">
          <thead>
            <tr>
              <th>Name</th>
              <th>Email</th>
              <th>Role</th>
              <th>Status</th>
              <th>Joined</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {users.content.map((u) => (
              <Fragment key={u.id}>
                <tr>
                  <td>{u.firstName} {u.lastName}</td>
                  <td>{u.email}</td>
                  <td>{u.role}</td>
                  <td><span className={`badge badge-${u.enabled ? 'active' : 'closed'}`}>{u.enabled ? 'Enabled' : 'Disabled'}</span></td>
                  <td>{new Date(u.createdAt).toLocaleDateString()}</td>
                  <td style={{ display: 'flex', gap: 8 }}>
                    <button className="btn btn-secondary" onClick={() => toggleExpand(u.id)}>
                      {expandedUser === u.id ? 'Hide accounts' : 'View accounts'}
                    </button>
                    <button
                      className={u.enabled ? 'btn btn-danger' : 'btn btn-primary'}
                      onClick={() => setUserEnabled(u.id, !u.enabled)}
                    >
                      {u.enabled ? 'Disable' : 'Enable'}
                    </button>
                  </td>
                </tr>
                {expandedUser === u.id && (
                  <tr>
                    <td colSpan={6} style={{ background: '#FAFAF8' }}>
                      {userAccounts.length === 0 ? (
                        <div className="helper-text" style={{ padding: '8px 0' }}>This user has no accounts.</div>
                      ) : (
                        <table className="ledger" style={{ margin: '6px 0' }}>
                          <thead>
                            <tr>
                              <th>Account</th>
                              <th>Number</th>
                              <th>Type</th>
                              <th>Balance</th>
                              <th>Status</th>
                              <th>Change status</th>
                            </tr>
                          </thead>
                          <tbody>
                            {userAccounts.map((a) => (
                              <tr key={a.id}>
                                <td>{a.accountName}</td>
                                <td className="account-number">{a.accountNumber}</td>
                                <td>{a.accountType}</td>
                                <td>${Number(a.balance).toFixed(2)}</td>
                                <td><span className={`badge badge-${a.status.toLowerCase()}`}>{a.status}</span></td>
                                <td>
                                  <select
                                    value={a.status}
                                    onChange={(e) => setAccountStatus(a.id, e.target.value)}
                                  >
                                    {ACCOUNT_STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
                                  </select>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                    </td>
                  </tr>
                )}
              </Fragment>
            ))}
          </tbody>
        </table>

        <div style={{ display: 'flex', gap: 8, marginTop: 16, alignItems: 'center' }}>
          <button className="btn btn-secondary" disabled={pageIndex === 0} onClick={() => setPageIndex((p) => p - 1)}>
            Previous
          </button>
          <span className="helper-text">Page {users.number + 1} of {Math.max(users.totalPages, 1)}</span>
          <button className="btn btn-secondary" disabled={pageIndex + 1 >= users.totalPages} onClick={() => setPageIndex((p) => p + 1)}>
            Next
          </button>
        </div>
      </div>
    </div>
  );
}
