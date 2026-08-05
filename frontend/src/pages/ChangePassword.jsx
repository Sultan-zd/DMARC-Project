import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Lock, Loader2, KeyRound } from 'lucide-react';
import PasswordField from '../components/ui/PasswordField';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Login.css';

/**
 * Where an account lands when its password was set by somebody else.
 *
 * An administrator-issued password travels through a channel nobody controls — a
 * chat message, a phone call, a sticky note. Until it is replaced, the person who
 * issued it can still sign in as this user.
 */
const ChangePassword = () => {
  usePageTitle('Choose a new password');
  const navigate = useNavigate();
  const { token, clearPasswordChange } = useAuth();
  const { showToast } = useToast();

  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');

    if (next !== confirm) {
      setError('The two new passwords do not match.');
      return;
    }

    setSubmitting(true);
    try {
      await api.changeOwnPassword(token, current, next);
      clearPasswordChange?.();
      showToast('Password changed', 'success');
      navigate('/dashboard', { replace: true });
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
          <KeyRound size={34} className="register-done-icon" />
          <h1>Choose your own password</h1>
          <p className="login-eyebrow">Required before continuing</p>
        </div>

        <p className="register-note">
          Your password was set by an administrator. Replace it with one only you know.
        </p>

        <form onSubmit={handleSubmit} className="login-form">
          {error && <div className="error-message shake">{error}</div>}

          <div className="input-group">
            <input
              type="password"
              value={current}
              onChange={(e) => setCurrent(e.target.value)}
              placeholder="Current password"
              aria-label="Current password"
              autoComplete="current-password"
              required
            />
            <Lock size={18} className="input-icon" />
          </div>

          <PasswordField
            value={next}
            onChange={(e) => setNext(e.target.value)}
            placeholder="New password"
            label="New password"
          />

          <div className="input-group">
            <input
              type="password"
              value={confirm}
              onChange={(e) => setConfirm(e.target.value)}
              placeholder="Confirm new password"
              aria-label="Confirm new password"
              autoComplete="new-password"
              required
            />
            <Lock size={18} className="input-icon" />
          </div>

          <button type="submit" className="login-button" disabled={submitting}>
            {submitting ? <Loader2 size={18} className="spin" /> : null}
            {submitting ? 'Saving…' : 'Set new password'}
          </button>
        </form>
      </div>
    </div>
  );
};

export default ChangePassword;
