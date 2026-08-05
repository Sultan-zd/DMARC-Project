import { useEffect, useState } from 'react';
import { useAuth } from '../context/AuthContext';
import * as api from '../services/api';

/**
 * What the running deployment reports about itself.
 *
 * The answer is the same for every caller and changes only on restart, so the first
 * fetch is shared: the footer and the Settings page ask for it independently and
 * one request serves both.
 */
let cached = null;
let inFlight = null;

export const resetSystemInfoCache = () => {
  cached = null;
  inFlight = null;
};

const useSystemInfo = () => {
  const { token } = useAuth();
  const [info, setInfo] = useState(cached);

  useEffect(() => {
    if (!token || cached) return undefined;

    let cancelled = false;
    inFlight = inFlight ?? api.getSystemInfo(token);
    inFlight
      .then((data) => {
        cached = data;
        if (!cancelled) setInfo(data);
      })
      .catch(() => {
        // Nothing here is load-bearing; callers fall back to showing nothing.
        inFlight = null;
      });

    return () => { cancelled = true; };
  }, [token]);

  return info;
};

export default useSystemInfo;
