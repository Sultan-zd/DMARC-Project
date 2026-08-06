import React, { useState } from 'react';
import { Link, useSearchParams, useNavigate } from 'react-router-dom';
import { Loader2, CheckCircle2, XCircle, KeyRound } from 'lucide-react';
import PasswordField from '../components/ui/PasswordField';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Login.css';

/**
 * Sets a new password from an emailed link.
 *
 * <p>No current password is asked for: not having it is the situation. What stands
 * in its place is the token, which only reached this person because it was sent to
 * the mailbox the account was registered with.
 *
 * <p>Unlike the verification page, the request is not fired on mount — it is fired
 * when the form is submitted. So there is no StrictMode double-invocation to guard
 * against here: nothing is spent until somebody presses the button.
 */
const ResetPassword = () => {
  usePageTitle('Choose a new password');

  const [params] = useSearchParams();
  const token = params.get('token');
  const navigate = useNavigate();

  const [password, setPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [status, setStatus] = useState(token ? 'ready' : 'no-token');
  const [error, setError] = useState('');

  const submit = async (event) => {
    event.preventDefault();
    setError('');

    if (password !== confirm) {
      setError('The two passwords do not match.');
      return;
    }

    setStatus('working');
    try {
      await api.resetPassword(token, password);
      setStatus('done');
      // Long enough to read the confirmation, short enough not to feel stuck.
      setTimeout(() => navigate('/login', { replace: true }), 2500);
    } catch (err) {
      // 400 and 409 both mean this link will never work again, so offering the
      // form once more would only waste the person's time. Anything else — a
      // rejected password, a rate limit — leaves the form up to try again.
      setStatus(err.status === 400 || err.status === 409 ? 'dead' : 'ready');
      setError(err.message);
    }
  };

  if (status === 'no-token') {
    return (
      <div className="login-container">
        <div className="login-background"></div>
        <div className="login-card">
          <div className="login-header">
            <img src="/brand/logo-teknologiia.png" alt="Teknologiia"
                 className="login-logo" width="576" height="64" />
            <XCircle size={40} className="register-error-icon" />
            <h1>Link incomplete</h1>
          </div>
          <p className="register-note">
            This address is missing its reset token. Open the link from the email
            exactly as it was sent, or ask for a new one.
          </p>
          <Link to="/forgot-password" className="login-button" style={{ textDecoration: 'none' }}>
            Ask for a new link
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="login-container">
      <div className="login-background"></div>
      <div className="login-card">
        <div className="login-header">
          <img src="/brand/logo-teknologiia.png" alt="Teknologiia"
               className="login-logo" width="576" height="64" />

          {status === 'done' && <CheckCircle2 size={40} className="register-done-icon" />}
          {status === 'dead' && <XCircle size={40} className="register-error-icon" />}

          <h1>
            {status === 'done' ? 'Password changed'
              : status === 'dead' ? 'Link no longer valid'
                : 'Choose a new password'}
          </h1>
        </div>

        {status === 'done' && (
          <>
            <p className="register-note">
              Your password has been changed. Taking you to the sign-in page…
            </p>
            <Link to="/login" className="login-button" style={{ textDecoration: 'none' }}>
              Sign in now
            </Link>
          </>
        )}

        {status === 'dead' && (
          <>
            <p className="register-note">{error}</p>
            <p className="register-hint">
              Reset links work once and expire after an hour. Asking for a new one
              takes a moment.
            </p>
            <Link to="/forgot-password" className="login-button" style={{ textDecoration: 'none' }}>
              Ask for a new link
            </Link>
          </>
        )}

        {(status === 'ready' || status === 'working') && (
          <form onSubmit={submit} className="login-form">
            {error && <div className="error-message shake">{error}</div>}

            <p className="register-note">
              <KeyRound size={14} /> You are not asked for the old password — following
              this link from your mailbox is what proves the account is yours.
            </p>

            <PasswordField
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="New password"
              label="New password"
            />

            <PasswordField
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              placeholder="Repeat the new password"
              label="Confirm new password"
              showChecklist={false}
            />

            <button type="submit" className="login-button"
                    disabled={status === 'working' || !password || !confirm}>
              {status === 'working'
                ? <Loader2 className="spinner" size={20} />
                : 'Set my new password'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
};

export default ResetPassword;
