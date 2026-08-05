import React, { useEffect, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { LayoutDashboard, FileText, Bell, Users, LogOut, Search, Settings, Moon, Sun, Menu, X, Server } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { isAdmin, roleLabel } from '../../utils/roles';
import { useTheme } from '../../context/ThemeContext';
import { useDomain } from '../../context/DomainContext';
import * as api from '../../services/api';
import './Sidebar.css';

const Sidebar = () => {
  const { user, logout, token } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const { selectedDomain, setSelectedDomain } = useDomain();
  const navigate = useNavigate();
  const [unreadCount, setUnreadCount] = useState(0);
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    if (!token) return;

    const fetchAlertCount = async () => {
      try {
        // The endpoint is authenticated, and it answers with the full breakdown
        // { total, unread, critical, high } — the badge wants the unread figure.
        const counts = await api.getAlertCount(token);
        setUnreadCount(counts?.unread ?? 0);
      } catch (error) {
        console.error("Failed to fetch alert count", error);
      }
    };
    fetchAlertCount();
  }, [token]);

  // Close sidebar on route change (mobile)
  useEffect(() => {
    setMobileOpen(false);
  }, [navigate]);

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  const closeMobile = () => setMobileOpen(false);

  return (
    <>
      {/* Mobile hamburger button */}
      <button 
        className="sidebar-hamburger" 
        onClick={() => setMobileOpen(true)}
        aria-label="Open menu"
      >
        <Menu size={24} />
      </button>

      {/* Mobile overlay */}
      {mobileOpen && (
        <div className="sidebar-overlay" onClick={closeMobile} />
      )}

      <aside className={`sidebar ${mobileOpen ? 'sidebar-open' : ''}`}>
        <div className="sidebar-brand">
          <img
            src="/brand/logo-teknologiia.png"
            alt="Teknologiia"
            className="brand-logo"
            width="576"
            height="64"
          />
          <span className="brand-product">DMARC Dashboard</span>

          {/* Mobile close button */}
          <button className="sidebar-close" onClick={closeMobile} aria-label="Close menu">
            <X size={20} />
          </button>
        </div>

        {selectedDomain && (
          <div className="sidebar-filter-indicator">
            <span className="filter-label">Active Filter:</span>
            <div className="filter-pill">
              <span className="filter-domain-name" title={selectedDomain}>{selectedDomain}</span>
              <button 
                className="filter-clear" 
                onClick={() => setSelectedDomain(null)}
                aria-label="Clear filter"
              >
                <X size={14} />
              </button>
            </div>
          </div>
        )}

        <nav className="sidebar-nav">
          {/* The product itself. An operator account holds no reports,
              analyses or alerts of its own, so these pages would only ever
              show them an empty version of somebody else's job. */}
          {!user?.platform_operator && (<>
          <NavLink to="/dashboard" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
            <LayoutDashboard size={20} />
            Dashboard
          </NavLink>
          <NavLink to="/reports" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
            <FileText size={20} />
            Reports
          </NavLink>
          <NavLink to="/alerts" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
            <Bell size={20} />
            Alerts
            {unreadCount > 0 && <span className="nav-badge">{unreadCount}</span>}
          </NavLink>
          <NavLink to="/analysis" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
            <Search size={20} />
            <span>Analysis</span>
          </NavLink>
          {isAdmin(user) && (
            <NavLink to="/admin" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
              <Users size={20} />
              Admin
            </NavLink>
          )}
          
          <div className="nav-divider" />
          
          </>)}

          {/* Only for whoever runs the service; the flag comes from the
              server, so it cannot be granted from inside the application. */}
          {user?.platform_operator && (
            <NavLink to="/platform" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
              <Server size={20} />
              <span>Platform</span>
            </NavLink>
          )}

          <NavLink to="/settings" className={({ isActive }) => isActive ? 'active' : ''} onClick={closeMobile}>
            <Settings size={20} />
            Settings
          </NavLink>
        </nav>

        <div className="sidebar-footer">
          {/* Dark mode toggle */}
          <button className="theme-btn" onClick={toggleTheme} title={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}>
            {theme === 'dark' ? <Sun size={16} /> : <Moon size={16} />}
            <span>{theme === 'dark' ? 'Light Mode' : 'Dark Mode'}</span>
          </button>

          <div className="sidebar-user">
            <div className="avatar">
              {user?.username ? user.username.charAt(0).toUpperCase() : 'U'}
            </div>
            <div className="user-info">
              <div className="user-name">{user?.username || 'User'}</div>
              <div className="user-role">{roleLabel(user?.role)}</div>
            </div>
          </div>
          <button onClick={handleLogout} className="logout-btn">
            <LogOut size={16} />
            Logout
          </button>
        </div>
      </aside>
    </>
  );
};

export default Sidebar;
