import React, { useEffect, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { Shield, LayoutDashboard, FileText, Bell, Users, LogOut, Search } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import * as api from '../../services/api';
import './Sidebar.css';

const Sidebar = () => {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const [unreadCount, setUnreadCount] = useState(0);

  useEffect(() => {
    const fetchAlertCount = async () => {
      try {
        const count = await api.getAlertCount();
        setUnreadCount(count);
      } catch (error) {
        console.error("Failed to fetch alert count", error);
      }
    };
    fetchAlertCount();
  }, []);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <aside className="sidebar">
      <div className="sidebar-brand">
        <div className="brand-icon">
          <Shield size={24} color="white" />
        </div>
        <div>
          <h1>Teknologiia</h1>
          <span>Security</span>
        </div>
      </div>

      <nav className="sidebar-nav">
        <NavLink to="/" end className={({ isActive }) => isActive ? 'active' : ''}>
          <LayoutDashboard size={20} />
          Dashboard
        </NavLink>
        <NavLink to="/reports" className={({ isActive }) => isActive ? 'active' : ''}>
          <FileText size={20} />
          Reports
        </NavLink>
        <NavLink to="/alerts" className={({ isActive }) => isActive ? 'active' : ''}>
          <Bell size={20} />
          Alerts
          {unreadCount > 0 && <span className="nav-badge">{unreadCount}</span>}
        </NavLink>
        <NavLink to="/analysis" className={({ isActive }) => isActive ? 'active' : ''}>
          <Search size={20} />
          <span>Analysis</span>
        </NavLink>
        {user?.role === 'admin' && (
          <NavLink to="/admin" className={({ isActive }) => isActive ? 'active' : ''}>
            <Users size={20} />
            Admin
          </NavLink>
        )}
      </nav>

      <div className="sidebar-footer">
        <div className="sidebar-user">
          <div className="avatar">
            {user?.username ? user.username.charAt(0).toUpperCase() : 'U'}
          </div>
          <div className="user-info">
            <div className="user-name">{user?.username || 'User'}</div>
            <div className="user-role">{user?.role || 'user'}</div>
          </div>
        </div>
        <button onClick={handleLogout} className="logout-btn">
          <LogOut size={16} />
          Logout
        </button>
      </div>
    </aside>
  );
};

export default Sidebar;
