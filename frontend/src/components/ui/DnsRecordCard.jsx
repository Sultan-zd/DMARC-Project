import React, { useState } from 'react';
import { CheckCircle, XCircle, AlertTriangle, HelpCircle, ChevronDown, ChevronUp } from 'lucide-react';
import './ui.css';

const DnsRecordCard = ({ type, status, rawRecord, parsed, summary }) => {
  const [expanded, setExpanded] = useState(false);

  const statusConfig = {
    found: { icon: CheckCircle, color: 'success', className: 'success' },
    pass: { icon: CheckCircle, color: 'success', className: 'success' },
    error: { icon: XCircle, color: 'danger', className: 'danger' },
    fail: { icon: XCircle, color: 'danger', className: 'danger' },
    warning: { icon: AlertTriangle, color: 'warning', className: 'warning' },
    not_found: { icon: HelpCircle, color: 'muted', className: 'muted' },
  };

  const config = statusConfig[status] || statusConfig.not_found;
  const Icon = config.icon;

  const formatKey = (key) => {
    const keyMap = {
      keySizeBits: 'Key Size',
      hasPublicKey: 'Has Public Key',
      v: 'Version',
      p: 'Policy / Public Key',
      rua: 'Aggregate Reports (rua)',
      ruf: 'Forensic Reports (ruf)',
      sp: 'Subdomain Policy',
      pct: 'Percentage',
      mechanisms: 'Mechanisms',
      selector: 'Selector',
      domain: 'Domain',
      l: 'Logo URL (BIMI)',
      a: 'Certificate URL (VMC)',
      records: 'MX Records',
      count: 'Count'
    };
    return keyMap[key] || key;
  };

  const renderValue = (key, value) => {
    if (key === 'keySizeBits') {
      return `${value} bits`;
    }
    if (key === 'l' && typeof value === 'string' && value.startsWith('http')) {
      return (
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <a href={value} target="_blank" rel="noopener noreferrer">{value}</a>
          <img src={value} alt="BIMI Logo Preview" style={{ width: '32px', height: '32px', objectFit: 'contain', background: '#fff', borderRadius: '4px', border: '1px solid #e2e8f0' }} />
        </div>
      );
    }
    if ((key === 'rua' || key === 'ruf' || key === 'a') && typeof value === 'string' && value.startsWith('http')) {
       return <a href={value} target="_blank" rel="noopener noreferrer">{value}</a>;
    }
    return value?.toString() || 'N/A';
  };

  return (
    <div className={`dns-record-card ${config.className}`}>
      <div className="dns-record-header" onClick={() => setExpanded(!expanded)}>
        <div className="dns-record-title">
          <Icon className={`dns-record-status ${config.className}`} size={24} />
          <h3>{type}</h3>
        </div>
        <button className="dns-record-toggle">
          {expanded ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
        </button>
      </div>
      
      <div className="dns-record-summary">
        {summary}
      </div>

      {expanded && (
        <div className="dns-record-details">
          {rawRecord && (
            <div className="dns-record-raw">
              <h4>Raw Record</h4>
              <code>{rawRecord}</code>
            </div>
          )}
          
          {parsed && Object.keys(parsed).length > 0 && (
            <div className="dns-record-parsed">
              <h4>Details</h4>
              <div className="dns-parsed-grid">
                {Object.entries(parsed).map(([key, value]) => (
                  <div key={key} className="dns-parsed-item">
                    <span className="dns-parsed-key">{formatKey(key)}:</span>
                    <span className="dns-parsed-value">{renderValue(key, value)}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default DnsRecordCard;
