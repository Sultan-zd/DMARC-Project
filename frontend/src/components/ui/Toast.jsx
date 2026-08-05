import React, { useEffect, useState } from 'react';
import { useToast } from '../../context/ToastContext';
import { CheckCircle, AlertTriangle, Info, XCircle, X } from 'lucide-react';
import './Toast.css';

const iconMap = {
  success: CheckCircle,
  error: XCircle,
  warning: AlertTriangle,
  info: Info
};

const Toast = ({ id, message, type = 'info', duration }) => {
  const [isExiting, setIsExiting] = useState(false);
  const { dismissToast } = useToast();

  useEffect(() => {
    if (duration > 0) {
      const exitTimer = setTimeout(() => {
        setIsExiting(true);
      }, duration - 400);

      return () => clearTimeout(exitTimer);
    }
  }, [duration]);

  const handleDismiss = () => {
    setIsExiting(true);
    setTimeout(() => dismissToast(id), 350);
  };

  const Icon = iconMap[type] || Info;

  return (
    <div 
      className={`toast toast-${type} ${isExiting ? 'toast-exit' : 'toast-enter'}`}
      role="alert"
      aria-live="polite"
    >
      <div className="toast-icon">
        <Icon size={18} />
      </div>
      <p className="toast-message">{message}</p>
      <button className="toast-close" onClick={handleDismiss} aria-label="Dismiss notification">
        <X size={14} />
      </button>
    </div>
  );
};

const ToastContainer = () => {
  const { toasts } = useToast();

  if (toasts.length === 0) return null;

  return (
    <div className="toast-container" aria-label="Notifications">
      {toasts.map((toast) => (
        <Toast key={toast.id} {...toast} />
      ))}
    </div>
  );
};

export default ToastContainer;
