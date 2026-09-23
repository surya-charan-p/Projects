import { Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './context/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import Sidebar from './components/Sidebar';
import Login from './pages/Login';
import Register from './pages/Register';
import Dashboard from './pages/Dashboard';
import Accounts from './pages/Accounts';
import AccountDetail from './pages/AccountDetail';
import Transfer from './pages/Transfer';
import Admin from './pages/Admin';

function AuthedLayout({ children }) {
  return (
    <ProtectedRoute>
      <Sidebar>{children}</Sidebar>
    </ProtectedRoute>
  );
}

function AppRoutes() {
  const { user } = useAuth();

  return (
    <Routes>
      <Route path="/login" element={user ? <Navigate to="/" replace /> : <Login />} />
      <Route path="/register" element={user ? <Navigate to="/" replace /> : <Register />} />

      <Route path="/" element={<AuthedLayout><Dashboard /></AuthedLayout>} />
      <Route path="/accounts" element={<AuthedLayout><Accounts /></AuthedLayout>} />
      <Route path="/accounts/:id" element={<AuthedLayout><AccountDetail /></AuthedLayout>} />
      <Route path="/transfer" element={<AuthedLayout><Transfer /></AuthedLayout>} />
      <Route
        path="/admin"
        element={
          <ProtectedRoute adminOnly>
            <Sidebar><Admin /></Sidebar>
          </ProtectedRoute>
        }
      />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
