import React from 'react';
import './ui.css';

const StatCard = ({ icon: Icon, value, label, trend, color = 'accent' }) => {
  return (
    <div className={`stat-card ${color}`}>
      <div className="stat-card-header">
        <div className="stat-card-icon">
          {Icon && <Icon size={20} />}
        </div>
      </div>
      <div className="stat-card-value">{value}</div>
      <div className="stat-card-label">{label}</div>
      {trend !== undefined && (
        <div className={`stat-card-trend ${trend >= 0 ? 'up' : 'down'}`}>
          {trend >= 0 ? '▲' : '▼'} {Math.abs(trend)}%
        </div>
      )}
    </div>
  );
};

export default StatCard;
