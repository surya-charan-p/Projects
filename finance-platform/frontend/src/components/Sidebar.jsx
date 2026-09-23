import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

export default function Sidebar({ children }) {
  const { user, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <div className="app-shell">
      <nav className="sidebar">
        <div className="brand">
          Ledger
          <small>Personal finance platform</small>
        </div>

        <NavLink to="/" end className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
          Dashboard
        </NavLink>
        <NavLink to="/accounts" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
          Accounts
        </NavLink>
        <NavLink to="/transfer" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
          Transfer funds
        </NavLink>
        {user?.role === 'ADMIN' && (
          <NavLink to="/admin" className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
            Admin
          </NavLink>
        )}

        <div className="nav-footer">
          <div style={{ marginBottom: 8 }}>{user?.firstName} {user?.lastName}</div>
          <button onClick={handleLogout}>Log out</button>
        </div>
      </nav>

      <main className="content">{children}</main>
    </div>
  );
}
