import React, { useState } from 'react';
import {
  User, Shield, Moon, Sun, Mail, KeyRound, Building2, Server, Check, X,
  AlertTriangle, Loader2, Calendar,
} from 'lucide-react';
import PasswordField from '../components/ui/PasswordField';
import TwoFactorCard from '../components/settings/TwoFactorCard';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../context/ThemeContext';
import { useToast } from '../context/ToastContext';
import { isAdmin, roleLabel, roleDescription, ROLE } from '../utils/roles';
import useSystemInfo from '../hooks/useSystemInfo';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Settings.css';

/**
 * What each role may do, matching the rules SecurityConfig actually enforces.
 *
 * Worth stating on screen: a viewer who does not know their account is read-only
 * reads a disabled button as a broken page.
 */
const PERMISSIONS = [
  { label: 'View the dashboard, reports and alerts', roles: [ROLE.ADMIN, ROLE.ANALYST, ROLE.VIEWER] },
  { label: 'Export reports as CSV or PDF', roles: [ROLE.ADMIN, ROLE.ANALYST, ROLE.VIEWER] },
  { label: 'Run a domain analysis', roles: [ROLE.ADMIN, ROLE.ANALYST] },
  { label: 'Upload reports and trigger mailbox ingestion', roles: [ROLE.ADMIN, ROLE.ANALYST] },
  { label: 'Mark alerts as read', roles: [ROLE.ADMIN, ROLE.ANALYST] },
  { label: 'Invite colleagues and claim email domains', roles: [ROLE.ADMIN] },
  { label: 'Create, disable and remove accounts', roles: [ROLE.ADMIN] },
];

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleDateString('en-GB', { day: 'numeric', month: 'long', year: 'numeric' })
    : '—';

/**
 * Settings this deployment still carries from development.
 *
 * Each one is read from the running process, so the list empties itself as the
 * settings are corrected rather than needing anyone to tick it off.
 */
const pendingChecks = (info) => [
  !info.jwtSecretProvided && {
    title: 'No signing key is configured.',
    detail: 'A throwaway key is generated at startup, so every session ends when the '
      + 'process restarts — and anyone who reads the log can forge a token.',
    fix: 'JWT_SECRET',
  },
  info.schemaAutoUpdate && {
    title: 'The database schema updates itself.',
    detail: 'Hibernate may alter live tables at startup. A mistyped entity then rewrites '
      + 'real data without anyone approving it.',
    fix: 'DB_DDL_AUTO=validate',
  },
  info.corsAllowsLocalhost && {
    title: 'A development origin can still call this API.',
    detail: 'localhost is on the allowed list, which has no meaning once the site is '
      + 'published and only widens what a browser will send.',
    fix: 'app.cors.origins',
  },
].filter(Boolean);

const Settings = () => {
  usePageTitle('Settings');
  const { user, token } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const { showToast } = useToast();
  const info = useSystemInfo();

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const role = String(user?.role ?? '').toUpperCase();

  const changePassword = async (event) => {
    event.preventDefault();
    setError('');

    if (next !== confirm) {
      setError('The two new passwords do not match.');
      return;
    }

    setSaving(true);
    try {
      await api.changeOwnPassword(token, current, next);
      setCurrent('');
      setNext('');
      setConfirm('');
      showToast('Password changed', 'success');
    } catch (err) {
      setError(err.message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="settings-page">
      <div className="settings-header">
        <h1>Settings</h1>
        <p className="subtitle">Your account, your access and this deployment</p>
      </div>

      <div className="settings-grid">
        <div className="settings-col">
          {/* ── Profile ── */}
          <section className="settings-card glass-card">
            <h2 className="settings-card-title"><User size={20} /> Profile</h2>

            <div className="profile-info">
              <div className="profile-avatar">
                {user?.username ? user.username.charAt(0).toUpperCase() : 'U'}
              </div>
              <div className="profile-details">
                <div className="profile-field">
                  <span className="field-label">Username</span>
                  <span className="field-value">{user?.username || '—'}</span>
                </div>
                <div className="profile-field">
                  <span className="field-label">Email</span>
                  <span className="field-value"><Mail size={14} /> {user?.email || '—'}</span>
                </div>
                <div className="profile-field">
                  <span className="field-label">Organization</span>
                  <span className="field-value">
                    <Building2 size={14} /> {user?.organization || '—'}
                  </span>
                </div>
                <div className="profile-field">
                  <span className="field-label">Member since</span>
                  <span className="field-value">
                    <Calendar size={14} /> {formatDate(user?.created_at)}
                  </span>
                </div>
                <div className="profile-field">
                  <span className="field-label">Role</span>
                  <span className={`role-tag role-${role.toLowerCase()}`}>
                    <Shield size={12} /> {roleLabel(role)}
                  </span>
                </div>
              </div>
            </div>

            <p className="settings-note">{roleDescription(role)}</p>
          </section>

          {/* ── Password ── */}
          <section className="settings-card glass-card">
            <h2 className="settings-card-title"><KeyRound size={20} /> Password</h2>
            <p className="settings-description">
              Changing it here signs nothing else out — sessions last
              {info ? ` ${info.sessionMinutes} minutes` : ' a fixed period'} and then expire on
              their own.
            </p>

            <form onSubmit={changePassword} className="password-form">
              {error && <div className="settings-error">{error}</div>}

              <label className="field-block">
                <span>Current password</span>
                <PasswordField
                  value={current}
                  onChange={(e) => setCurrent(e.target.value)}
                  placeholder="Current password"
                  label="Current password"
                  autoComplete="current-password"
                  showChecklist={false}
                />
              </label>

              <label className="field-block">
                <span>New password</span>
                <PasswordField
                  value={next}
                  onChange={(e) => setNext(e.target.value)}
                  placeholder="New password"
                  label="New password"
                />
              </label>

              <label className="field-block">
                <span>Confirm new password</span>
                <PasswordField
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                  placeholder="Repeat the new password"
                  label="Confirm new password"
                  showChecklist={false}
                />
              </label>

              <button type="submit" className="btn btn-primary" disabled={saving}>
                {saving ? <><Loader2 size={16} className="spin" /> Saving…</> : 'Change password'}
              </button>
            </form>
          </section>

          <TwoFactorCard />

          {/* ── Appearance ── */}
          <section className="settings-card glass-card">
            <h2 className="settings-card-title">
              {theme === 'dark' ? <Moon size={20} /> : <Sun size={20} />} Appearance
            </h2>
            <div className="setting-row">
              <div className="setting-info">
                <span className="setting-label">Dark mode</span>
                <span className="setting-description">
                  Kept on this browser. Printed reports stay light either way.
                </span>
              </div>
              <button
                className={`theme-toggle ${theme === 'dark' ? 'active' : ''}`}
                onClick={toggleTheme}
                aria-label={`Switch to ${theme === 'dark' ? 'light' : 'dark'} mode`}
              >
                <span className="toggle-track">
                  <span className="toggle-thumb">
                    {theme === 'dark' ? <Moon size={12} /> : <Sun size={12} />}
                  </span>
                </span>
              </button>
            </div>
          </section>
        </div>

        <div className="settings-col">
          {/* ── What this account may do ── */}
          <section className="settings-card glass-card">
            <h2 className="settings-card-title"><Shield size={20} /> Your access</h2>
            <p className="settings-description">
              Enforced by the API, not only hidden in the interface.
            </p>
            <ul className="permission-list">
              {PERMISSIONS.map((permission) => {
                const allowed = permission.roles.includes(role);
                return (
                  <li key={permission.label} className={allowed ? 'allowed' : 'denied'}>
                    {allowed ? <Check size={15} /> : <X size={15} />}
                    <span>{permission.label}</span>
                  </li>
                );
              })}
            </ul>
          </section>

          {/* ── This deployment ── */}
          <section className="settings-card glass-card">
            <h2 className="settings-card-title"><Server size={20} /> System</h2>
            {info ? (
              <>
                <dl className="system-list">
                  <div><dt>Application</dt><dd>{info.application}</dd></div>
                  <div><dt>Version</dt><dd className="mono">{info.version}</dd></div>
                  <div><dt>Database</dt><dd className="mono">{info.database}</dd></div>
                  <div><dt>Java</dt><dd className="mono">{info.javaVersion}</dd></div>
                  <div><dt>DNS resolver</dt><dd className="mono">{info.dnsResolver}</dd></div>
                  <div><dt>Session length</dt><dd>{info.sessionMinutes} minutes</dd></div>
                  <div><dt>Invitation validity</dt><dd>{info.invitationTtlHours} hours</dd></div>
                  <div><dt>Sign-up link validity</dt><dd>{info.verificationTtlHours} hours</dd></div>
                  <div><dt>Max upload</dt><dd>{info.maxUploadSize}</dd></div>
                  <div>
                    <dt>Public scan limit</dt>
                    <dd>{info.scanRateCapacity} burst, {info.scanRateRefillPerMinute}/min</dd>
                  </div>
                </dl>

                {isAdmin(user) && pendingChecks(info).length > 0 && (
                  <div className="deployment-warnings">
                    <h3><AlertTriangle size={15} /> Before publishing</h3>
                    {pendingChecks(info).map((check) => (
                      <p key={check.title}>
                        <strong>{check.title}</strong> {check.detail} Set <code>{check.fix}</code>.
                      </p>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <p className="settings-note">Reading deployment details…</p>
            )}
          </section>
        </div>
      </div>
    </div>
  );
};

export default Settings;
