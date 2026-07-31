import React, { useState, useEffect } from 'react';
import Header from '../components/layout/Header';
import ScoreGauge from '../components/ui/ScoreGauge';
import DnsRecordCard from '../components/ui/DnsRecordCard';
import SkeletonLoader from '../components/ui/SkeletonLoader';
import { useAuth } from '../context/AuthContext';
import * as api from '../services/api';
import { Search, Shield, Globe, RefreshCw, History, ArrowRight, Clock, AlertTriangle, Info, CheckCircle, BookOpen, Printer } from 'lucide-react';
import './DomainAnalysis.css';

const DomainAnalysis = () => {
  const { token } = useAuth();
  const [domain, setDomain] = useState('');
  const [results, setResults] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [history, setHistory] = useState([]);
  const [showHistory, setShowHistory] = useState(false);
  const [loadingHistory, setLoadingHistory] = useState(false);

  useEffect(() => {
    loadHistory();
  }, [token]);

  const loadHistory = async () => {
    try {
      setLoadingHistory(true);
      const res = await api.getAnalysisHistory(token);
      if (res && res.items) {
        setHistory(res.items);
      }
    } catch (err) {
      console.error("Error loading history:", err);
    } finally {
      setLoadingHistory(false);
    }
  };

  const handleAnalyze = async (e) => {
    e.preventDefault();
    if (!domain || !domain.includes('.')) {
      setError('Please enter a valid domain name (e.g. example.com).');
      return;
    }

    setLoading(true);
    setError('');
    setResults(null);

    try {
      const data = await api.analyzeDomain(token, domain.toLowerCase().trim());
      setResults(data);
      loadHistory();
    } catch (err) {
      setError(err.message || 'An error occurred while analyzing the domain.');
    } finally {
      setLoading(false);
    }
  };

  const loadPastAnalysis = async (id) => {
    setLoading(true);
    setError('');
    setResults(null);
    try {
      const data = await api.getAnalysis(token, id);
      setResults(data);
      setDomain(data.domain);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (err) {
      setError(err.message || 'Error loading analysis.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="analysis-page">
      <div className="app-header">
        <Header title="Email Security Analysis" subtitle="Verify DMARC/SPF/DKIM configuration for any domain" />
      </div>

      <div className="analysis-search glass-card">
        <h2>Analyze a domain</h2>
        <form onSubmit={handleAnalyze} className="search-form">
          <div className="search-input-wrapper">
            <Globe className="search-input-icon" size={20} />
            <input
              type="text"
              className="search-input"
              placeholder="Enter a domain name (e.g. google.com)"
              value={domain}
              onChange={(e) => setDomain(e.target.value)}
              disabled={loading}
            />
          </div>
          <button type="submit" className="search-btn" disabled={loading}>
            {loading ? (
              <>
                <RefreshCw size={20} className="spin" />
                Analyzing...
              </>
            ) : (
              <>
                <Search size={20} />
                Analyze
              </>
            )}
          </button>
        </form>
        {error && <div className="error-message fade-in" style={{ marginTop: '1rem', color: 'var(--danger)' }}>{error}</div>}
      </div>

      {loading && !results && (
        <div className="loading-state fade-in">
          <SkeletonLoader type="card" />
          <div className="records-section" style={{ marginTop: '2rem' }}>
            <SkeletonLoader type="card" />
            <SkeletonLoader type="card" />
            <SkeletonLoader type="card" />
          </div>
        </div>
      )}

      {results && (
        <div className="results-container fade-in">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
            <h2 style={{ margin: 0 }}>Analysis Results: {results.domain}</h2>
            <button 
              className="search-btn btn-print" 
              style={{ minWidth: 'auto', padding: '0.75rem 1.5rem', gap: '0.5rem', background: 'var(--text-primary)' }}
              onClick={() => window.print()}
            >
              <Printer size={18} />
              Export PDF
            </button>
          </div>

          <div className="results-grid">
            <div className="score-section">
              <h3 style={{ marginBottom: '1.5rem', textAlign: 'center' }}>Security Score</h3>
              <ScoreGauge
                score={results.score?.score || 0}
                grade={results.score?.grade || 'N/A'}
                color={results.score?.color || 'blue'}
                size={220}
              />
              <p style={{ marginTop: '1.5rem', textAlign: 'center', color: 'var(--text-secondary)' }}>
                Based on the configuration of email security DNS records.
              </p>
            </div>

            <div className="records-section">
              {results.records?.map((record, index) => (
                <div className={`fade-in stagger-${(index % 6) + 1}`} key={index}>
                  <DnsRecordCard {...record} />
                </div>
              ))}
            </div>
          </div>

          {results.recommendations && results.recommendations.length > 0 && (
            <div className="recommendations-section fade-in">
              <h3 style={{ marginBottom: '1.5rem', display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <Shield size={20} color="var(--accent-primary)" />
                Recommendations
              </h3>
              <div className="recommendations-list">
                {results.recommendations.map((rec, index) => (
                  <div key={index} className={`recommendation-item ${rec.severity}`}>
                    <div className="rec-icon">
                      {rec.severity === 'critical' && <AlertTriangle size={20} color="var(--danger)" />}
                      {rec.severity === 'warning' && <AlertTriangle size={20} color="var(--warning)" />}
                      {rec.severity === 'info' && <Info size={20} color="var(--info)" />}
                      {rec.severity === 'success' && <CheckCircle size={20} color="var(--success)" />}
                    </div>
                    <div className="rec-content">
                      <p className="rec-message">{rec.message}</p>
                      {rec.action && <p className="rec-action"><strong>Action:</strong> {rec.action}</p>}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Educational Section - How It Works */}
      <div className="educational-section fade-in">
        <div className="edu-header">
          <BookOpen size={28} color="var(--accent-primary)" />
          <h2>How This Analysis Works</h2>
        </div>
        
        <div className="edu-grid">
          <div className="edu-card">
            <h3>1. The DNS Lookup Process</h3>
            <p>
              When you submit a domain, our backend engine performs real-time live DNS lookups using <strong>Google's public DNS servers (8.8.8.8)</strong> to ensure we see exactly what the rest of the internet sees.
            </p>
            <p>
              We don't use cached data. We actively query the domain's nameservers for specific <code>TXT</code> and <code>MX</code> records that are fundamental to modern email authentication.
            </p>
          </div>

          <div className="edu-card">
            <h3>2. DMARC Check (Domain-based Message Authentication)</h3>
            <p>
              We query the <code>TXT</code> record at <span className="edu-highlight">_dmarc.yourdomain.com</span>.
            </p>
            <div className="edu-code-block">
              v=DMARC1; p=reject; rua=mailto:dmarc@yourdomain.com;
            </div>
            <p>
              <strong>Scoring Impact:</strong><br/>
              • <code>p=reject</code>: Highest security, blocks all spoofed emails (+40 pts)<br/>
              • <code>p=quarantine</code>: Sends spoofed emails to spam folder (+30 pts)<br/>
              • <code>p=none</code>: Monitoring only mode, offers no active protection (+15 pts)<br/>
              • Missing DMARC: Leaves the domain entirely vulnerable to spoofing (0 pts)
            </p>
          </div>

          <div className="edu-card">
            <h3>3. SPF Check (Sender Policy Framework)</h3>
            <p>
              We query the root domain for a <code>TXT</code> record starting with <code>v=spf1</code>. This record acts as a public whitelist of IP addresses authorized to send emails on your behalf.
            </p>
            <div className="edu-code-block">
              v=spf1 ip4:192.168.0.1 include:_spf.google.com ~all
            </div>
            <p>
              <strong>Scoring Impact:</strong><br/>
              • <code>-all</code> (Hard Fail): Strict enforcement (+30 pts)<br/>
              • <code>~all</code> (Soft Fail): Standard enforcement (+20 pts)<br/>
              • <code>?all</code> (Neutral) or <code>+all</code> (Allow All): Highly insecure (0 pts)
            </p>
          </div>

          <div className="edu-card">
            <h3>4. DKIM Check (DomainKeys Identified Mail)</h3>
            <p>
              DKIM uses cryptographic signatures to ensure emails aren't tampered with in transit. We test common selectors (e.g., <code>google</code>, <code>default</code>, <code>selector1</code>).
            </p>
            <p>
              <strong>Key Size Detection:</strong> We extract and decode the public key to determine its cryptographic strength. Keys smaller than 2048 bits (like 1024-bit) are considered weak and vulnerable.
            </p>
            <p>
              <strong>Scoring Impact:</strong><br/>
              Finding a valid DKIM key awards <strong>+20 pts</strong>. A weak key (&lt;2048 bits) incurs a slight penalty (-5 pts).
            </p>
          </div>

          <div className="edu-card">
            <h3>5. MX Check (Mail Exchange)</h3>
            <p>
              We check for <code>MX</code> records to verify if the domain is configured to receive emails. If a domain sends emails but cannot receive them, some aggressive spam filters might penalize it.
            </p>
            <p>
              <strong>Scoring Impact:</strong><br/>
              Valid MX records indicate a healthy, fully-configured mail domain (<strong>+10 pts</strong>).
            </p>
          </div>

          <div className="edu-card">
            <h3>6. BIMI Check (Brand Indicators)</h3>
            <p>
              BIMI (Brand Indicators for Message Identification) allows you to display your corporate logo next to your messages in supported inboxes (like Gmail or Yahoo).
            </p>
            <p>
              <strong>Scoring Impact:</strong><br/>
              A valid BIMI record at <code>default._bimi.yourdomain.com</code> provides a <strong>+5 pts</strong> bonus for maximizing brand trust and visibility.
            </p>
          </div>

          <div className="edu-card">
            <h3>7. Final Grade Calculation</h3>
            <p>
              The system aggregates the points and assigns a letter grade (Max 100):
            </p>
            <table className="edu-table">
              <tbody>
                <tr><th>A+ (90-100)</th><td>Perfect configuration (Reject + HardFail + Strong DKIM)</td></tr>
                <tr><th>A  (80-89)</th><td>Strong protection (Quarantine + SoftFail)</td></tr>
                <tr><th>B  (60-79)</th><td>Good, but using p=none (Monitoring mode)</td></tr>
                <tr><th>C  (40-59)</th><td>Missing major protocols (No DMARC)</td></tr>
                <tr><th>F  (&lt;40)</th><td>Critical security risks, highly vulnerable</td></tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <div className="history-section">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
          <h3>Analysis History</h3>
          <button className="history-toggle" onClick={() => setShowHistory(!showHistory)}>
            <History size={18} />
            {showHistory ? 'Hide' : 'Show'}
          </button>
        </div>

        {showHistory && (
          <div className="history-content fade-in">
            {loadingHistory ? (
              <SkeletonLoader type="table" lines={5} />
            ) : history.length > 0 ? (
              <div className="table-responsive">
                <table className="history-table">
                  <thead>
                    <tr>
                      <th>Domain</th>
                      <th>Score</th>
                      <th>Date</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.map((item) => (
                      <tr key={item.id}>
                        <td className="font-mono">{item.domain}</td>
                        <td>
                          <span className={`status-badge ${item.score?.color || 'info'}`}>
                            <span className="dot"></span>
                            {item.score?.grade || 'N/A'} - {item.score?.score || 0}/100
                          </span>
                        </td>
                        <td>{new Date(item.analyzed_at).toLocaleString('en-US')}</td>
                        <td>
                          <button 
                            className="btn-text" 
                            onClick={() => loadPastAnalysis(item.id)}
                            style={{ display: 'flex', alignItems: 'center', gap: '0.25rem', color: 'var(--accent-primary)', background: 'none', border: 'none', cursor: 'pointer' }}
                          >
                            View <ArrowRight size={14} />
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : (
              <p style={{ color: 'var(--text-muted)', textAlign: 'center', padding: '2rem' }}>
                No analysis history available.
              </p>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

export default DomainAnalysis;
