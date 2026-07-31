import React, { createContext, useContext, useState, useEffect } from 'react';
import * as api from '../services/api';

const AuthContext = createContext();

export const AuthProvider = ({ children }) => {
  const [user, setUser] = useState(null);
  const [token, setToken] = useState(localStorage.getItem('dmarc_token') || null);
  const [loading, setLoading] = useState(true);

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

  const login = async (username, password) => {
    try {
      const response = await api.login(username, password);
      const newToken = response.access_token;
      localStorage.setItem('dmarc_token', newToken);
      setToken(newToken);
      const userData = await api.getMe(newToken);
      setUser(userData);
      return true;
    } catch (error) {
      console.error("Login failed", error);
      throw error;
    }
  };

  const logout = () => {
    localStorage.removeItem('dmarc_token');
    setToken(null);
    setUser(null);
  };

  return (
    <AuthContext.Provider value={{ user, token, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => useContext(AuthContext);
