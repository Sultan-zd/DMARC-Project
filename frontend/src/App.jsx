import { Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import { isAdmin } from './utils/roles';
import Sidebar from './components/layout/Sidebar';
import Footer from './components/layout/Footer';
import ToastContainer from './components/ui/Toast';
import ErrorBoundary from './components/ui/ErrorBoundary';
import './App.css';

import Login from './pages/Login';
import Register from './pages/Register';
import VerifyEmail from './pages/VerifyEmail';
import ChangePassword from './pages/ChangePassword';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import AcceptInvitation from './pages/AcceptInvitation';
import Landing from './pages/Landing';
import ScanResult from './pages/ScanResult';
import Dashboard from './pages/Dashboard';
import Reports from './pages/Reports';
import ReportDetail from './pages/ReportDetail';
import Alerts from './pages/Alerts';
import Admin from './pages/Admin';
import Platform from './pages/Platform';
import DomainAnalysis from './pages/DomainAnalysis';
import Settings from './pages/Settings';
import NotFound from './pages/NotFound';

const ProtectedRoute = () => {
  const { token, loading, mustChangePassword } = useAuth();

  if (loading) {
    return <div>Loading...</div>;
  }

  if (!token) {
    return <Navigate to="/login" replace />;
  }

  if (mustChangePassword) {
    return <Navigate to="/change-password" replace />;
  }

  return (
    <div className="app-layout">
      <Sidebar />
      <div className="main-content">
        <div className="main-content-inner">
          <Outlet />
        </div>
        <Footer />
      </div>
    </div>
  );
};

const AdminRoute = () => {
  const { token, user, loading, mustChangePassword } = useAuth();

  if (loading) {
    return <div>Loading...</div>;
  }

  if (!token) {
    return <Navigate to="/login" replace />;
  }

  if (mustChangePassword) {
    return <Navigate to="/change-password" replace />;
  }
  
  if (!isAdmin(user)) {
    return <Navigate to="/dashboard" replace />;
  }

  return (
    <div className="app-layout">
      <Sidebar />
      <div className="main-content">
        <div className="main-content-inner">
          <Outlet />
        </div>
        <Footer />
      </div>
    </div>
  );
};

function App() {
  return (
    <ErrorBoundary>
      <ToastContainer />
      <Routes>
        {/* ─── Public ─── */}
        <Route path="/" element={<Landing />} />
        <Route path="/scan/:domain" element={<ScanResult />} />
        <Route path="/login" element={<Login />} />
        <Route path="/register" element={<Register />} />
        <Route path="/verify" element={<VerifyEmail />} />
        <Route path="/change-password" element={<ChangePassword />} />
        <Route path="/forgot-password" element={<ForgotPassword />} />
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/invitation" element={<AcceptInvitation />} />

        {/* ─── Authenticated ─── */}
        <Route element={<ProtectedRoute />}>
          <Route path="/dashboard" element={<Dashboard />} />
          <Route path="/reports" element={<Reports />} />
          <Route path="/reports/:id" element={<ReportDetail />} />
          <Route path="/alerts" element={<Alerts />} />
          <Route path="/analysis" element={<DomainAnalysis />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="/platform" element={<Platform />} />
        </Route>

        <Route path="/admin" element={<AdminRoute />}>
          <Route index element={<Admin />} />
        </Route>

        <Route path="*" element={<NotFound />} />
      </Routes>
    </ErrorBoundary>
  );
}

export default App;
