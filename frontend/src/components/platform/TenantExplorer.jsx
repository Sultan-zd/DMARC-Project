import React, { useCallback, useEffect, useState } from 'react';
import {
  Building2, ChevronRight, ShieldCheck, Minus, Trash2, Globe, Inbox,
  AlertTriangle, Mail, Loader2,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { roleLabel } from '../../utils/roles';
import * as api from '../../services/api';
import './TenantExplorer.css';

const number = (value) => new Intl.NumberFormat('en-GB').format(value ?? 0);

const when = (value) =>
  value ? new Date(value).toLocaleString('en-GB', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }) : '—';

/**
 * Every organization on the platform, and everyone inside them.
 *
 * <p>Accounts in full: who they are, what they may do, whether a second factor is
 * in force, whether they still owe a password change. What an organization holds
 * is shown as volume — reports, analyses, messages — and never opened. That line
 * is the one the landing page draws for anyone signing up, and an operator console
 * that quietly stepped over it would make the promise conditional on trust rather
 * than on design.
 */
const TenantExplorer = ({ currentUsername, onChange }) => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [tenants, setTenants] = useState(null);
  const [openId, setOpenId] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setTenants(await api.getPlatformOrganizations(token));
    } catch (err) {
      showToast(err.message, 'error');
    }
  }, [token, showToast]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const run = async (fn, message) => {
    setBusy(true);
    try {
      await fn();
      await load();
      onChange?.();
      showToast(message, 'success');
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  if (!tenants) return null;

  return (
    <section className="platform-card tenant-explorer">
      <h2><Building2 size={16} /> Organizations · {tenants.length}</h2>
      <p className="explorer-lead">
        Every organization on this deployment. Open one to see its accounts.
      </p>

      <ul className="explorer-list">
        {tenants.map((t) => {
          const open = openId === t.id;
          const failing = t.mailboxLastRunOk === false;

          return (
            <li key={t.id} className={open ? 'open' : ''}>
              <button
                className="explorer-head"
                onClick={() => setOpenId(open ? null : t.id)}
                aria-expanded={open}
              >
                <ChevronRight size={16} className="explorer-chevron" />
                <span className="explorer-name">{t.name}</span>
                <span className="explorer-counts">
                  {t.accounts.length} {t.accounts.length === 1 ? 'account' : 'accounts'}
                  {t.reports > 0 && ` · ${number(t.reports)} reports`}
                  {t.analyses > 0 && ` · ${t.analyses} analyses`}
                </span>
                {failing && (
                  <span className="explorer-flag"><AlertTriangle size={13} /> mailbox failing</span>
                )}
                {/* "empty" has to mean the same thing here as it does to the
                    delete button, or the label promises something the action
                    then refuses. An organization with no accounts but with
                    analyses still holds someone's work. */}
                {t.removable && <span className="explorer-flag empty">empty</span>}
                {!t.removable && t.accounts.length === 0 && (
                  <span className="explorer-flag empty">no accounts</span>
                )}
              </button>

              {open && (
                <div className="explorer-body">
                  <dl className="explorer-facts">
                    <div><dt>Created</dt><dd>{when(t.createdAt)}</dd></div>
                    <div><dt>Reports held</dt><dd>{number(t.reports)}</dd></div>
                    <div><dt>Messages covered</dt><dd>{number(t.messagesCovered)}</dd></div>
                    <div><dt>Analyses run</dt><dd>{number(t.analyses)}</dd></div>
                    <div>
                      <dt><Inbox size={12} /> Mailbox</dt>
                      <dd className={failing ? 'failing' : ''}>
                        {t.mailboxAddress
                          ? <>{t.mailboxAddress}{failing && ' — last run failed'}</>
                          : 'none configured'}
                      </dd>
                    </div>
                    {t.invitationsPending > 0 && (
                      <div><dt><Mail size={12} /> Invitations</dt><dd>{t.invitationsPending} pending</dd></div>
                    )}
                  </dl>

                  {t.claimedDomains.length > 0 && (
                    <p className="explorer-domains">
                      <Globe size={13} />
                      {t.claimedDomains.map((d) => (
                        <span key={d.domain} className={d.verified ? 'verified' : ''}>
                          {d.domain}{d.verified ? '' : ' (unverified)'}
                        </span>
                      ))}
                    </p>
                  )}

                  {t.accounts.length > 0 ? (
                    <div className="table-responsive">
                      <table className="explorer-accounts">
                        <thead>
                          <tr>
                            <th>Account</th>
                            <th>Role</th>
                            <th>Two-step</th>
                            <th>Status</th>
                            <th>Added</th>
                            <th><span className="sr-only">Actions</span></th>
                          </tr>
                        </thead>
                        <tbody>
                          {t.accounts.map((a) => {
                            const self = a.username === currentUsername;
                            return (
                              <tr key={a.id}>
                                <td>
                                  <div className="account-name">
                                    {a.username}
                                    {self && <span className="account-you">you</span>}
                                    {a.mustChangePassword && (
                                      <span className="account-pending" title="Has not chosen their own password yet">
                                        password pending
                                      </span>
                                    )}
                                  </div>
                                  <div className="account-email">{a.email || '—'}</div>
                                </td>
                                <td>{roleLabel(a.role)}</td>
                                <td>
                                  {a.twoFactorEnabled
                                    ? <span className="mfa-yes"><ShieldCheck size={14} /> On</span>
                                    : <span className="mfa-no"><Minus size={13} /> Off</span>}
                                </td>
                                <td>
                                  <span className={`status-pill ${a.active ? 'active' : 'inactive'}`}>
                                    {a.active ? 'Active' : 'Disabled'}
                                  </span>
                                </td>
                                <td className="muted">{when(a.createdAt)}</td>
                                <td className="explorer-actions">
                                  <button
                                    className="btn-tiny"
                                    disabled={busy || self}
                                    title={self ? 'You cannot disable your own account.' : 'Toggle access'}
                                    onClick={() => run(
                                      () => api.setPlatformAccountActive(token, a.id, !a.active),
                                      `${a.username} ${a.active ? 'disabled' : 'enabled'}`,
                                    )}
                                  >
                                    {a.active ? 'Disable' : 'Enable'}
                                  </button>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </div>
                  ) : (
                    <p className="explorer-none">
                      No accounts. A sign-up that was never completed leaves a shell like this.
                    </p>
                  )}

                  {t.removable && (
                    <button
                      className="btn-text-danger explorer-remove"
                      disabled={busy}
                      onClick={() => run(
                        () => api.removePlatformOrganization(token, t.id),
                        `${t.name} removed`,
                      )}
                    >
                      {busy ? <Loader2 size={14} className="spin" /> : <Trash2 size={14} />}
                      Remove this empty organization
                    </button>
                  )}

                  <p className="explorer-boundary">
                    Volume only — the reports themselves stay with this organization.
                  </p>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
};

export default TenantExplorer;
