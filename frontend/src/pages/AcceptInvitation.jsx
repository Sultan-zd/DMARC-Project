import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { User, Loader2, XCircle, Users, CheckCircle2 } from 'lucide-react';
import PasswordField from '../components/ui/PasswordField';
import { roleLabel, roleDescription } from '../utils/roles';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Login.css';

/**
 * Where an invitation link lands: /invitation?token=…
 *
 * The invitee has no account yet, so this page is public and the token is the
 * credential. Accepting puts the new account inside the inviting organization —
 * which is what prevents a colleague from signing up separately and ending up in a
 * second organization with the same company name and an empty dashboard.
 */
const AcceptInvitation = () => {
  usePageTitle('Join your team');
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const token = params.get('token');

  const [invitation, setInvitation] = useState(null);
  const [status, setStatus] = useState('loading');
  const [error, setError] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!token) {
      setStatus('invalid');
      setError('This link is missing its invitation token.');
      return;
    }

    let cancelled = false;
    api.getInvitation(token)
      .then((data) => {
        if (cancelled) return;
        setInvitation(data);
        // A sensible starting point; the invitee can change it.
        setUsername(data.email.split('@')[0].replace(/[^a-zA-Z0-9._-]/g, ''));
        setStatus('ready');
      })
      .catch((err) => {
        if (cancelled) return;
        setError(err.message);
        setStatus('invalid');
      });

    return () => { cancelled = true; };
  }, [token]);

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await api.acceptInvitation(token, username, password);
      setStatus('joined');
    } catch (err) {
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

          {status === 'loading' && <Loader2 size={34} className="spin register-done-icon" />}
          {status === 'invalid' && <XCircle size={34} className="register-error-icon" />}
          {status === 'ready' && <Users size={34} className="register-done-icon" />}
          {status === 'joined' && <CheckCircle2 size={34} className="register-done-icon" />}

          <h1>
            {status === 'loading' && 'Checking your invitation…'}
            {status === 'invalid' && 'Invitation not valid'}
            {status === 'ready' && `Join ${invitation.organization}`}
            {status === 'joined' && 'You are in'}
          </h1>

          {status === 'ready' && (
            <p className="login-eyebrow">as {roleLabel(invitation.role)}</p>
          )}
        </div>

        {status === 'invalid' && (
          <>
            <p className="register-note">{error}</p>
            <Link to="/login" className="login-button" style={{ textDecoration: 'none' }}>
              Back to sign in
            </Link>
          </>
        )}

        {status === 'joined' && (
          <>
            <p className="register-note">
              Your account is active — no email confirmation needed, since the invitation
              already reached you.
            </p>
            <button className="login-button" onClick={() => navigate('/login')}>
              Sign in
            </button>
          </>
        )}

        {status === 'ready' && (
          <>
            <p className="register-note">
              Invited as <strong>{invitation.email}</strong>.
              {' '}{roleDescription(invitation.role)}
            </p>

            <form onSubmit={handleSubmit} className="login-form">
              {error && <div className="error-message shake">{error}</div>}

              <div className="input-group">
                <input
                  type="text"
                  value={username}
                  onChange={(e) => setUsername(e.target.value)}
                  placeholder="Choose a username"
                  aria-label="Username"
                  autoComplete="username"
                  required
                />
                <User size={18} className="input-icon" />
              </div>

              <PasswordField
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Choose a password"
                label="Password"
              />

              <button type="submit" className="login-button" disabled={submitting}>
                {submitting ? <Loader2 size={18} className="spin" /> : null}
                {submitting ? 'Joining…' : `Join ${invitation.organization}`}
              </button>
            </form>
          </>
        )}
      </div>
    </div>
  );
};

export default AcceptInvitation;
