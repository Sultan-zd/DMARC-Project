import React, { useState, useEffect } from 'react';
import { AlertTriangle, Info, CheckCircle, Check } from 'lucide-react';
import * as api from '../services/api';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import usePageTitle from '../hooks/usePageTitle';
import './Alerts.css';

const Alerts = () => {
  usePageTitle('Security Alerts');
  const { token } = useAuth();
  const { showToast } = useToast();
  const [alerts, setAlerts] = useState([]);
  const [stats, setStats] = useState({ total: 0, unread: 0, critical: 0, high: 0 });
  const [loading, setLoading] = useState(true);
  const [filterSeverity, setFilterSeverity] = useState('all');
  const [filterRead, setFilterRead] = useState('all');

  const fetchAlerts = async () => {
    try {
      const params = {};
      if (filterSeverity !== 'all') params.severity = filterSeverity;
      if (filterRead === 'unread') params.is_read = false;
      else if (filterRead === 'read') params.is_read = true;

      const data = await api.getAlerts(token, params);
      const counts = await api.getAlertCount(token);
      
      setAlerts(data?.items || []);
      setStats(counts || { total: 0, unread: 0, critical: 0, high: 0 });
      setLoading(false);
    } catch (err) {
      console.error("Failed to load alerts", err);
      showToast('Failed to load alerts', 'error');
      setAlerts([]);
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchAlerts();
  }, [filterSeverity, filterRead]);

  const handleMarkAllRead = async () => {
    try {
      await api.markAllAlertsRead(token);
      showToast('All alerts marked as read', 'success');
      fetchAlerts();
    } catch (err) {
      console.error("Error marking all read", err);
      showToast('Failed to mark all as read', 'error');
    }
  };

  const handleMarkRead = async (id) => {
    try {
      await api.markAlertRead(token, id);
      setAlerts(alerts.map(a => a.id === id ? { ...a, is_read: true } : a));
      setStats({ ...stats, unread: Math.max(0, stats.unread - 1) });
    } catch (err) {
      console.error("Error marking alert read", err);
      showToast('Failed to mark alert as read', 'error');
    }
  };

  const getAlertIcon = (severity) => {
    if (severity === 'critical' || severity === 'high') {
      return <AlertTriangle size={20} />;
    }
    return <Info size={20} />;
  };

  const getTimeAgo = (dateString) => {
    const date = new Date(dateString);
    const now = new Date();
    const diffInSeconds = Math.floor((now - date) / 1000);
    
    if (diffInSeconds < 60) return "just now";
    if (diffInSeconds < 3600) return `${Math.floor(diffInSeconds / 60)}m ago`;
    if (diffInSeconds < 86400) return `${Math.floor(diffInSeconds / 3600)}h ago`;
    return `${Math.floor(diffInSeconds / 86400)}d ago`;
  };

  return (
    <div className="alerts-page">
      <div className="alerts-header">
        <div>
          <h1>Security Alerts</h1>
          <p className="subtitle">Findings that need someone to look at them</p>
          <p className="page-header-note">
            <Info size={14} aria-hidden="true" />
            <span>
              Raised automatically when an authentication failure rate or sending volume
              crosses a threshold, and by domain analyses you run while signed in.
            </span>
          </p>
        </div>
        <button className="btn btn-primary" onClick={handleMarkAllRead}>
          <Check size={18} /> Mark all as read
        </button>
      </div>

      <div className="alerts-stats">
        <div className="alert-stat-pill">
          <span className="stat-label">Total</span>
          <span className="stat-value">{stats.total}</span>
        </div>
        <div className="alert-stat-pill unread-stat">
          <span className="stat-label">Unread</span>
          <span className="stat-value">{stats.unread}</span>
        </div>
        <div className="alert-stat-pill critical-stat">
          <span className="stat-label">Critical</span>
          <span className="stat-value">{stats.critical}</span>
        </div>
        <div className="alert-stat-pill high-stat">
          <span className="stat-label">High</span>
          <span className="stat-value">{stats.high}</span>
        </div>
      </div>

      <div className="alerts-filters">
        <select 
          value={filterSeverity} 
          onChange={(e) => setFilterSeverity(e.target.value)}
          className="filter-select"
        >
          <option value="all">All severities</option>
          <option value="critical">Critical</option>
          <option value="high">High</option>
          <option value="medium">Medium</option>
          <option value="low">Low</option>
        </select>

        <select 
          value={filterRead} 
          onChange={(e) => setFilterRead(e.target.value)}
          className="filter-select"
        >
          <option value="all">All statuses</option>
          <option value="unread">Unread</option>
          <option value="read">Read</option>
        </select>
      </div>

      <div className="alerts-list">
        {loading ? (
          <div className="alerts-loading">Loading...</div>
        ) : alerts.length === 0 ? (
          <div className="alerts-empty glass-card">
            <CheckCircle size={48} color="var(--success, #10b981)" />
            <h2>No alerts</h2>
            <p>Everything looks good.</p>
          </div>
        ) : (
          alerts.map(alert => (
            <div key={alert.id} className={`alert-card ${alert.severity} ${!alert.is_read ? 'unread' : ''}`}>
              <div className="alert-icon-wrapper">
                {getAlertIcon(alert.severity)}
              </div>
              
              <div className="alert-content">
                <h3 className="alert-message">{alert.message}</h3>
                {alert.details && <p className="alert-details">{alert.details}</p>}
                
                <div className="alert-meta">
                  <span className="domain-badge">{alert.domain}</span>
                  <span className="timestamp">{getTimeAgo(alert.created_at)}</span>
                  {!alert.is_read && <span className="unread-dot"></span>}
                </div>
              </div>

              <div className="alert-actions">
                {!alert.is_read && (
                  <button className="mark-read-btn" onClick={() => handleMarkRead(alert.id)}>
                    Mark as read
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </div>
    </div>
  );
};

export default Alerts;
