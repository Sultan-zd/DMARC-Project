import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Mail, Loader2, MailCheck, ArrowLeft } from 'lucide-react';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Login.css';

/**
 * Asks for a reset link.
 *
 * <p>The confirmation says "if that address belongs to an account" rather than
 * "sent". The server refuses to distinguish a registered address from an unknown
 * one — otherwise this page becomes a way of asking whether any given person has
 * an account here, with no session — so the wording has to hold that line too. A
 * page that said "sent!" would leak exactly what the endpoint took care not to.
 */
const ForgotPassword = () => {
  usePageTitle('Forgot your password');

  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const submit = async (event) => {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await api.requestPasswordReset(email.trim());
      setSent(true);
    } catch (err) {
      // Only a refused request reaches here — a rate limit, or a malformed
      // address. An address that simply has no account resolves normally.
      setError(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-background"></div>
      <div className="login-card">
        <div className="login-header">
          <img src="/brand/logo-teknologiia.png" alt="Teknologiia"
               className="login-logo" width="576" height="64" />

          {sent && <MailCheck size={40} className="register-done-icon" />}

          <h1>{sent ? 'Check your mailbox' : 'Forgot your password'}</h1>
          {!sent && (
            <p className="login-eyebrow">A link to choose a new one</p>
          )}
        </div>

        {sent ? (
          <>
            <p className="register-note">
              If <strong>{email.trim()}</strong> belongs to an account, a reset link is
              on its way. It works once and expires in an hour.
            </p>
            <p className="register-hint">
              Nothing arrived? Check the spam folder, then try again — the address may
              not be the one the account was registered with.
            </p>
            <Link to="/login" className="login-button" style={{ textDecoration: 'none' }}>
              Back to sign in
            </Link>
          </>
        ) : (
          <form onSubmit={submit} className="login-form">
            {error && <div className="error-message shake">{error}</div>}

            <p className="register-note">
              Enter the address the account was registered with — not the username.
              We will email a link that lets you set a new password.
            </p>

            <div className="input-group">
              <Mail className="input-icon" size={20} />
              <input
                type="email"
                placeholder="you@company.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                aria-label="Email address"
                autoFocus
                required
              />
            </div>

            <button type="submit" className="login-button"
                    disabled={submitting || !email.trim()}>
              {submitting
                ? <Loader2 className="spinner" size={20} />
                : 'Send the reset link'}
            </button>

            <Link to="/login" className="mfa-back">
              <ArrowLeft size={14} /> Back to sign in
            </Link>
          </form>
        )}
      </div>
    </div>
  );
};

export default ForgotPassword;
