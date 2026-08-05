import React, { createContext, useContext, useState, useEffect } from 'react';
import * as api from '../services/api';

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(localStorage.getItem('dmarc_token') || null);
  const [loading, setLoading] = useState(true);
  // Set when the password was issued by an administrator: the app holds the user
  // at the change screen until they pick their own.
  const [mustChangePassword, setMustChangePassword] = useState(
    localStorage.getItem('dmarc_must_change') === 'true',
  );

  useEffect(() => {
    const validateToken = async () => {
      if (token) {
        try {
          const userData = await api.getMe(token);
          setUser(userData);
        } catch (error) {
          console.error("Token validation failed", error);
          localStorage.removeItem('dmarc_token');
          setToken(null);
          setUser(null);
        }
      }
      setLoading(false);
    };
    
    validateToken();
  }, [token]);

  /** Turns a successful sign-in response into a session. */
  const startSession = async (response) => {
    const newToken = response.access_token;
    const pending = response.must_change_password === true;
    setMustChangePassword(pending);
    localStorage.setItem('dmarc_must_change', String(pending));
    localStorage.setItem('dmarc_token', newToken);
    setToken(newToken);
    const account = await api.getMe(newToken);
    setUser(account);
    // Returned so the caller can send an operator to their own console rather
    // than to a dashboard that holds nothing of theirs.
    return { done: true, user: account };
  };

  /**
   * First stage of signing in.
   *
   * Returns `{ done: true }` when a session was granted, or
   * `{ done: false, mfaToken }` when the account needs its second factor. The
   * caller decides what to show; nothing is stored until the sign-in is complete.
   */
  const login = async (username, password) => {
    const response = await api.login(username, password);
    if (response.mfa_required) {
      return { done: false, mfaToken: response.mfa_token };
    }
    return startSession(response);
  };

  /** Second stage: the code from the authenticator app, or a recovery code. */
  const completeTwoFactor = async (mfaToken, code) =>
    startSession(await api.completeTwoFactor(mfaToken, code));

  /** Called once the user has set their own password. */
  const clearPasswordChange = () => {
    setMustChangePassword(false);
    localStorage.removeItem('dmarc_must_change');
  };

  const logout = () => {
    localStorage.removeItem('dmarc_token');
    localStorage.removeItem('dmarc_must_change');
    setToken(null);
    setUser(null);
    setMustChangePassword(false);
  };

  return (
    <AuthContext.Provider value={{
      user, token, loading, login, completeTwoFactor, logout,
      mustChangePassword, clearPasswordChange,
    }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
