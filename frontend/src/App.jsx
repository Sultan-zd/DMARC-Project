import { Routes, Route, Navigate, Outlet } from 'react-router-dom';
import { useAuth } from './context/AuthContext';
import Sidebar from './components/layout/Sidebar';
import './App.css';

import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Reports from './pages/Reports';
import ReportDetail from './pages/ReportDetail';
import Alerts from './pages/Alerts';
import Admin from './pages/Admin';
import DomainAnalysis from './pages/DomainAnalysis';

const ProtectedRoute = () => {
  const { token, loading } = useAuth();
  
  if (loading) {
    return <div>Loading...</div>;
  }
  
  if (!token) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="app-layout">
      <Sidebar />
      <div className="main-content">
        <Outlet />
      </div>
    </div>
  );
};

const AdminRoute = () => {
  const { token, user, loading } = useAuth();
  
  if (loading) {
    return <div>Loading...</div>;
  }
  
  if (!token) {
    return <Navigate to="/login" replace />;
  }
  
  if (user?.role !== 'admin') {
    return <Navigate to="/" replace />;
  }

  return (
    <div className="app-layout">
      <Sidebar />
      <div className="main-content">
        <Outlet />
      </div>
    </div>
  );
};

function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      
      <Route path="/" element={<ProtectedRoute />}>
        <Route index element={<Dashboard />} />
        <Route path="reports" element={<Reports />} />
        <Route path="reports/:id" element={<ReportDetail />} />
        <Route path="alerts" element={<Alerts />} />
        <Route path="analysis" element={<DomainAnalysis />} />
      </Route>
      
      <Route path="/admin" element={<AdminRoute />}>
        <Route index element={<Admin />} />
      </Route>
      
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default App;
