import React, { useCallback, useEffect, useState } from 'react';
import {
  Trash2, KeyRound, Copy, Check, ShieldAlert, Users, ShieldCheck, Minus,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { ROLE, roleLabel, roleDescription } from '../../utils/roles';
import * as api from '../../services/api';
import './UserManagement.css';

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
    : '—';

/**
 * The accounts that exist, and what can be done to them.
 *
 * <p>Creating one is deliberately not here. It used to sit alongside as a form that
 * generated a password for the administrator to pass on — which meant the password
 * travelled through a chat message or a phone call, and until it was changed the
 * person who issued it could sign in as that user. An invitation reaches the same
 * end without that: the invitee sets a password nobody else ever sees, the link
 * expires, and it can be revoked before it is used. The one thing direct creation
 * offered — working with no mail server — the invitation covers too, because the
 * link is shown on screen for the administrator to hand over.
 *
 * <p>Resetting a password stays, because it answers a different question: somebody
 * who already has an account is locked out of it.
 */
const UserManagement = () => {
  const { token, user: currentUser } = useAuth();
  const { showToast } = useToast();

  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [issued, setIssued] = useState(null);   // { username, password }
  const [copied, setCopied] = useState(false);

  const load = useCallback(async () => {
    try {
      setUsers(await api.getUsers(token) ?? []);
    } catch {
      showToast('Could not load accounts', 'error');
    } finally {
      setLoading(false);
    }
  }, [token, showToast]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const act = async (fn, successMessage) => {
    try {
      await fn();
      showToast(successMessage, 'success');
      load();
    } catch (err) {
      showToast(err.message, 'error');
    }
  };

  const handleReset = async (target) => {
    try {
      const { password } = await api.resetUserPassword(token, target.id);
      setIssued({ username: target.username, password });
      showToast(`New password issued for ${target.username}`, 'success');
    } catch (err) {
      showToast(err.message, 'error');
    }
  };

  const copyIssued = async () => {
    try {
      await navigator.clipboard.writeText(issued.password);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard access can be denied; the password is on screen regardless.
    }
  };

  return (
    <div className="user-management">
      {/* A generated password exists in readable form only here, only now. */}
      {issued && (
        <div className="issued-banner">
          <KeyRound size={20} />
          <div className="issued-body">
            <strong>New password for {issued.username}</strong>
            <p>
              Shown once — it is stored only as a hash and cannot be retrieved later.
              Share it over a channel the recipient controls; they must change it at
              first sign-in.
            </p>
            <code>{issued.password}</code>
          </div>
          <button className="issued-copy" onClick={copyIssued}>
            {copied ? <Check size={16} /> : <Copy size={16} />}
            {copied ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}

      <section className="admin-card">
        <div className="card-header-icon">
          <Users size={20} className="icon-blue" />
          <h2>Accounts</h2>
          {!loading && (
            <span className="card-count">
              {users.length} {users.length === 1 ? 'member' : 'members'}
            </span>
          )}
        </div>

        {loading ? (
          <p className="user-empty">Loading…</p>
        ) : users.length === 0 ? (
          <p className="user-empty">No accounts yet.</p>
        ) : (
          <div className="table-responsive">
            <table className="users-table">
              <thead>
                <tr>
                  <th className="col-user">User</th>
                  <th className="col-role">Role</th>
                  <th className="col-status">Status</th>
                  <th className="col-mfa">Two-step</th>
                  <th className="col-added">Added</th>
                  <th className="col-actions">Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => {
                  const self = u.username === currentUser?.username;
                  return (
                    <tr key={u.id}>
                      <td className="col-user">
                        <div className="user-name">
                          {u.username}{self && <span className="user-you">you</span>}
                        </div>
                        <div className="user-email">{u.email || '—'}</div>
                      </td>
                      <td className="col-role">
                        <select
                          className="role-select"
                          value={u.role}
                          disabled={self}
                          title={self ? 'You cannot change your own role.' : roleDescription(u.role)}
                          onChange={(e) => act(
                            () => api.changeUserRole(token, u.id, e.target.value),
                            `${u.username} is now ${roleLabel(e.target.value)}`,
                          )}
                        >
                          {Object.values(ROLE).map((r) => (
                            <option key={r} value={r}>{roleLabel(r)}</option>
                          ))}
                        </select>
                      </td>
                      <td className="col-status">
                        <button
                          className={`status-pill ${u.is_active ? 'active' : 'inactive'}`}
                          disabled={self}
                          title={self ? 'You cannot deactivate yourself.' : 'Toggle access'}
                          onClick={() => act(
                            () => api.setUserActive(token, u.id, !u.is_active),
                            `${u.username} ${u.is_active ? 'deactivated' : 'reactivated'}`,
                          )}
                        >
                          {u.is_active ? 'Active' : 'Disabled'}
                        </button>
                      </td>
                      <td className="col-mfa">
                        {/* Only the account holder can turn this on, so it is shown
                            rather than offered — an administrator who can see who is
                            unprotected can at least go and ask. */}
                        {u.two_factor_enabled ? (
                          <span className="mfa-yes" title="Second factor in force">
                            <ShieldCheck size={15} /> On
                          </span>
                        ) : (
                          <span className="mfa-no" title="Password only">
                            <Minus size={14} /> Off
                          </span>
                        )}
                      </td>
                      <td className="col-added">{formatDate(u.created_at)}</td>
                      <td className="col-actions">
                        <button
                          className="icon-btn"
                          title="Issue a new password"
                          onClick={() => handleReset(u)}
                        >
                          <KeyRound size={15} />
                        </button>
                        <button
                          className="icon-btn danger"
                          title={self ? 'You cannot delete your own account.' : 'Delete account'}
                          disabled={self}
                          onClick={() => act(
                            () => api.deleteUser(token, u.id),
                            `${u.username} deleted`,
                          )}
                        >
                          <Trash2 size={15} />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        <p className="role-legend">
          <ShieldAlert size={14} />
          <span>
            <strong>Administrator</strong> manages accounts and everything below ·
            <strong> Analyst</strong> runs analyses, imports reports, acts on alerts ·
            <strong> Viewer</strong> reads everything, changes nothing.
          </span>
        </p>
      </section>
    </div>
  );
};

export default UserManagement;
