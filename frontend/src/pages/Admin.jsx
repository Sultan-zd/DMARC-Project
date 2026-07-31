import React, { useState, useEffect } from 'react';
import { Play, UserPlus, ShieldAlert, CheckCircle } from 'lucide-react';
import * as api from '../services/api';
import { useAuth } from '../context/AuthContext';
import './Admin.css';

const Admin = () => {
  const { user, token } = useAuth();
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [ingestionStatus, setIngestionStatus] = useState(null);
  const [isIngesting, setIsIngesting] = useState(false);
  
  const [newUser, setNewUser] = useState({
    username: '',
    email: '',
    password: '',
    role: 'viewer'
  });
  const [formFeedback, setFormFeedback] = useState(null);

  useEffect(() => {
    const fetchUsers = async () => {
      try {
        const data = await api.getUsers(token);
        setUsers(data || []);
        setLoading(false);
      } catch (err) {
        console.error("Failed to load users", err);
        setLoading(false);
      }
    };
    if (user.role === 'admin') {
      fetchUsers();
    }
  }, []);

  if (user.role !== 'admin') {
    return (
      <div className="admin-access-denied glass-card">
        <ShieldAlert size={64} color="var(--danger)" />
        <h2>Access Denied</h2>
        <p>You do not have the necessary admin rights to view this page.</p>
      </div>
    );
  }

  const handleCreateUser = async (e) => {
    e.preventDefault();
    setFormFeedback(null);
    try {
      await api.createUser(token, newUser);
      setFormFeedback({ type: 'success', message: 'User created successfully!' });
      setNewUser({ username: '', email: '', password: '', role: 'viewer' });
      // Refresh users
      const data = await api.getUsers(token);
      setUsers(data || []);
    } catch (err) {
      setFormFeedback({ type: 'error', message: 'Error creating user.' });
    }
  };

  const handleManualIngestion = async () => {
    setIsIngesting(true);
    setIngestionStatus(null);
    try {
      const result = await api.triggerIngestion(token);
      setIngestionStatus({
        success: true,
        emailsProcessed: result.processed || 0,
        reportsStored: result.stored || 0
      });
    } catch (err) {
      setIngestionStatus({ success: false, error: 'Ingestion failed.' });
    } finally {
      setIsIngesting(false);
    }
  };

  const getRoleBadge = (role) => {
    const roles = {
      admin: 'role-admin',
      analyst: 'role-analyst',
      viewer: 'role-viewer'
    };
    return <span className={`role-badge ${roles[role] || 'role-viewer'}`}>{role}</span>;
  };

  return (
    <div className="admin-page">
      <div className="admin-header">
        <h1>Administration</h1>
        <p>User and system management</p>
      </div>

      <div className="admin-grid">
        <div className="admin-col-left">
          <div className="admin-card users-card">
            <h2>User List</h2>
            <div className="table-responsive">
              <table className="users-table">
                <thead>
                  <tr>
                    <th>User</th>
                    <th>Email</th>
                    <th>Role</th>
                    <th>Status</th>
                    <th>Created on</th>
                  </tr>
                </thead>
                <tbody>
                  {loading ? (
                    <tr><td colSpan="5" className="text-center">Loading...</td></tr>
                  ) : users.map(user => (
                    <tr key={user.id}>
                      <td className="font-medium">{user.username}</td>
                      <td>{user.email}</td>
                      <td>{getRoleBadge(user.role)}</td>
                      <td>
                        <span className={`status-pill ${user.is_active ? 'active' : 'inactive'}`}>
                          {user.is_active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td>{new Date(user.created_at).toLocaleDateString('en-US')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <div className="admin-col-right">
          <div className="admin-card create-user-card">
            <div className="card-header-icon">
              <UserPlus size={20} />
              <h2>Create User</h2>
            </div>
            
            <form onSubmit={handleCreateUser} className="admin-form">
              <div className="form-group">
                <label>Username</label>
                <input 
                  type="text" 
                  value={newUser.username}
                  onChange={(e) => setNewUser({...newUser, username: e.target.value})}
                  required 
                />
              </div>
              <div className="form-group">
                <label>Email</label>
                <input 
                  type="email" 
                  value={newUser.email}
                  onChange={(e) => setNewUser({...newUser, email: e.target.value})}
                  required 
                />
              </div>
              <div className="form-group">
                <label>Password</label>
                <input 
                  type="password" 
                  value={newUser.password}
                  onChange={(e) => setNewUser({...newUser, password: e.target.value})}
                  required 
                />
              </div>
              <div className="form-group">
                <label>Role</label>
                <select 
                  value={newUser.role}
                  onChange={(e) => setNewUser({...newUser, role: e.target.value})}
                >
                  <option value="admin">Administrator</option>
                  <option value="analyst">Analyst</option>
                  <option value="viewer">Viewer</option>
                </select>
              </div>
              
              <button type="submit" className="btn btn-primary w-full">
                Create User
              </button>

              {formFeedback && (
                <div className={`form-feedback ${formFeedback.type}`}>
                  {formFeedback.type === 'success' ? <CheckCircle size={16} /> : <ShieldAlert size={16} />}
                  <span>{formFeedback.message}</span>
                </div>
              )}
            </form>
          </div>

          <div className="admin-card ingestion-card">
            <div className="card-header-icon">
              <Play size={20} className="icon-blue" />
              <h2>Manual Ingestion</h2>
            </div>
            <p className="description">
              Immediately triggers the IMAP mailbox connection to fetch and parse new DMARC reports.
            </p>
            
            <button 
              className="btn btn-secondary w-full" 
              onClick={handleManualIngestion}
              disabled={isIngesting}
            >
              {isIngesting ? 'Ingestion in progress...' : (
                <><Play size={16} fill="currentColor" /> Start Ingestion</>
              )}
            </button>

            {ingestionStatus && (
              <div className={`ingestion-result ${ingestionStatus.success ? 'success' : 'error'}`}>
                {ingestionStatus.success ? (
                  <ul>
                    <li>Emails processed: <strong>{ingestionStatus.emailsProcessed}</strong></li>
                    <li>Reports stored: <strong>{ingestionStatus.reportsStored}</strong></li>
                  </ul>
                ) : (
                  <p>{ingestionStatus.error}</p>
                )}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default Admin;
