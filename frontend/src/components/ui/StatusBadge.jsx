import React from 'react';
import './ui.css';

const StatusBadge = ({ status }) => {
  const getStatusColor = (s) => {
    const statusMap = {
      pass: 'success',
      fail: 'danger',
      softfail: 'warning',
      none: 'muted',
      neutral: 'info',
      quarantine: 'warning',
      reject: 'danger'
    };
    return statusMap[s?.toLowerCase()] || 'muted';
  };

  const colorClass = getStatusColor(status);

  return (
    <div className={`status-badge ${colorClass}`}>
      <span className="dot"></span>
      <span>{status || 'Unknown'}</span>
    </div>
  );
};

export default StatusBadge;
