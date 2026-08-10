import React, { useCallback, useEffect, useMemo, useState } from 'react';
import Header from '../components/layout/Header';
import ScoreGauge from '../components/ui/ScoreGauge';
import DnsRecordCard from '../components/ui/DnsRecordCard';
import SkeletonLoader from '../components/ui/SkeletonLoader';
import AnalysisPosture from '../components/analysis/AnalysisPosture';
import AnalysisHistory from '../components/analysis/AnalysisHistory';
import ScoringModelPanel from '../components/analysis/ScoringModelPanel';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useDomain } from '../context/DomainContext';
import { canEdit } from '../utils/roles';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import {
  Search, Globe, RefreshCw, AlertTriangle, Info, CheckCircle, Printer, Clock, Lock, KeyRound,
} from 'lucide-react';
import './DomainAnalysis.css';

/** Most urgent first — the order the work should be done in. */
const SEVERITY_ORDER = ['critical', 'warning', 'info', 'success'];

const SEVERITY_META = {
  critical: { label: 'Must fix', icon: AlertTriangle },
  warning: { label: 'Should fix', icon: AlertTriangle },
  info: { label: 'Worth knowing', icon: Info },
  success: { label: 'Already correct', icon: CheckCircle },
};

const DomainAnalysis = () => {
  usePageTitle('Domain Analysis');
  const { token, user } = useAuth();
  const { showToast } = useToast();
  const { setSelectedDomain } = useDomain();

  const [domain, setDomain] = useState('');
  const [dkimSelector, setDkimSelector] = useState('');
  const [results, setResults] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [history, setHistory] = useState([]);
  const [loadingHistory, setLoadingHistory] = useState(true);

  const mayAnalyse = canEdit(user);

  const loadHistory = useCallback(async () => {
    try {
      setLoadingHistory(true);
      const res = await api.getAnalysisHistory(token, { page_size: 50 });
      setHistory(res?.items ?? []);
    } catch {
      showToast('Failed to load analysis history', 'error');
    } finally {
      setLoadingHistory(false);
    }
  }, [token, showToast]);

  useEffect(() => { if (token) loadHistory(); }, [token, loadHistory]);

  /** Domains already looked at, newest first — one click to re-check. */
  const recentDomains = useMemo(() => {
    const seen = [];
    history.forEach((item) => {
      if (!seen.includes(item.domain)) seen.push(item.domain);
    });
    return seen.slice(0, 6);
  }, [history]);

  const runAnalysis = async (target) => {
    const value = target.toLowerCase().trim();
    if (!value.includes('.')) {
      setError('Enter a full domain name, such as example.com.');
      return;
    }

    setLoading(true);
    setError('');
    setResults(null);
    try {
      const data = await api.analyzeDomain(token, value, dkimSelector.trim());
      setResults(data);
      setDomain(value);
      setSelectedDomain(value);
      showToast(`${value} scored ${data.score?.score}/100`, 'success');
      loadHistory();
    } catch (err) {
      setError(err.message || 'The analysis could not be completed.');
      showToast('Analysis failed', 'error');
    } finally {
      setLoading(false);
    }
  };

  const openPastAnalysis = async (id) => {
    setLoading(true);
    setError('');
    setResults(null);
    try {
      const data = await api.getAnalysis(token, id);
      setResults(data);
      setDomain(data.domain);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } catch (err) {
      setError(err.message || 'That analysis could not be opened.');
    } finally {
      setLoading(false);
    }
  };

  // Grouped so the page leads with what is broken rather than with what passed.
  const groupedRecommendations = useMemo(() => {
    const groups = new Map();
    (results?.recommendations ?? []).forEach((rec) => {
      if (!groups.has(rec.severity)) groups.set(rec.severity, []);
      groups.get(rec.severity).push(rec);
    });
    return SEVERITY_ORDER
      .filter((severity) => groups.has(severity))
      .map((severity) => [severity, groups.get(severity)]);
  }, [results]);

  return (
    <div className="analysis-page">
      <div className="app-header">
        <Header
          title="Email Security Analysis"
          subtitle="How a domain’s DNS is configured right now"
          note="A live DNS check, independent of the Dashboard. A domain can score well here and still show failures there — that means the configuration is sound but a legitimate sender is missing from it."
        />
      </div>

      <AnalysisPosture history={history} />

      <div className="analysis-search glass-card">
        <div className="search-heading">
          <h2>Analyse a domain</h2>
          <p>
            Reads DMARC, SPF, DKIM, MX and BIMI straight from public DNS. Nothing is
            sent to the domain, and no mailbox access is needed.
          </p>
        </div>

        <form
          onSubmit={(e) => { e.preventDefault(); runAnalysis(domain); }}
          className="search-form"
        >
          <div className="search-input-wrapper">
            <Globe className="search-input-icon" size={20} />
            <input
              type="text"
              className="search-input"
              placeholder="example.com"
              value={domain}
              onChange={(e) => setDomain(e.target.value)}
              disabled={loading || !mayAnalyse}
              aria-label="Domain to analyse"
            />
          </div>
          <button type="submit" className="search-btn" disabled={loading || !mayAnalyse}>
            {loading
              ? <><RefreshCw size={20} className="spin" /> Analysing…</>
              : <><Search size={20} /> Analyse</>}
          </button>
        </form>

        {/* The one input that turns a guess into an answer. DNS offers no way to
            list selectors, so a domain signed under an uncommon name reports
            "could not determine" however long the guess list gets — but the person
            running the check usually knows the name. */}
        <div className="selector-hint">
          <label htmlFor="dkim-selector">
            <KeyRound size={14} /> DKIM selector <span>optional</span>
          </label>
          <input
            id="dkim-selector"
            type="text"
            className="selector-input"
            placeholder="e.g. selector1, google, k1"
            value={dkimSelector}
            onChange={(e) => setDkimSelector(e.target.value)}
            disabled={loading || !mayAnalyse}
          />
          <p>
            Selectors cannot be listed from DNS — a key is only found by asking for
            its name. Leave this empty and common names are tried; if none answers,
            the result says so rather than claiming there is no key. Yours appears in
            the <code>s=</code> field of a <code>DKIM-Signature</code> header on any
            message you have sent.
          </p>
        </div>

        {!mayAnalyse && (
          <p className="search-locked">
            <Lock size={14} />
            Your account can read analyses but not run them. An administrator or
            analyst can run a new check.
          </p>
        )}

        {recentDomains.length > 0 && mayAnalyse && (
          <div className="recent-domains">
            <span className="recent-label"><Clock size={13} /> Recently checked</span>
            {recentDomains.map((d) => (
              <button key={d} className="recent-chip" onClick={() => runAnalysis(d)} disabled={loading}>
                {d}
              </button>
            ))}
          </div>
        )}

        {error && <div className="error-message fade-in analysis-error">{error}</div>}
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
          <div className="results-bar">
            <div>
              <h2>{results.domain}</h2>
              <p>
                Checked {new Date(results.analyzed_at ?? Date.now()).toLocaleString('en-GB', {
                  day: '2-digit', month: 'short', year: 'numeric',
                  hour: '2-digit', minute: '2-digit',
                })}
                {results.analyzed_by ? ` by ${results.analyzed_by}` : ''}
              </p>
            </div>
            <button className="btn-print" onClick={() => window.print()}>
              <Printer size={18} /> Export PDF
            </button>
          </div>

          <div className="results-grid">
            <div className="score-section">
              <h3>Security score</h3>
              <ScoreGauge
                score={results.score?.score || 0}
                grade={results.score?.grade || 'N/A'}
                color={results.score?.color || 'blue'}
                size={220}
              />
              {results.score?.breakdown && (
                <ul className="score-breakdown">
                  {Object.entries(results.score.breakdown).map(([control, earned]) => (
                    <li key={control}>
                      <span className="breakdown-name">{control}</span>
                      <span className="breakdown-points">{earned}</span>
                    </li>
                  ))}
                </ul>
              )}
              <p className="score-note">
                Points earned out of those that applied. Controls that cannot be read
                are left out of both sides.
              </p>
            </div>

            <div className="records-section">
              {results.records?.map((record, index) => (
                <div className={`fade-in stagger-${(index % 6) + 1}`} key={record.type ?? index}>
                  <DnsRecordCard {...record} />
                </div>
              ))}
            </div>
          </div>

          {groupedRecommendations.length > 0 && (
            <div className="recommendations-section fade-in">
              <h3>What to do next</h3>
              {groupedRecommendations.map(([severity, items]) => {
                const meta = SEVERITY_META[severity] ?? SEVERITY_META.info;
                const Icon = meta.icon;
                return (
                  <div key={severity} className={`rec-group ${severity}`}>
                    <div className="rec-group-head">
                      <Icon size={16} />
                      <h4>{meta.label}</h4>
                      <span className="rec-group-count">{items.length}</span>
                    </div>
                    <div className="recommendations-list">
                      {items.map((rec, index) => (
                        <div key={index} className={`recommendation-item ${severity}`}>
                          <div className="rec-content">
                            {rec.category && <span className="rec-category">{rec.category}</span>}
                            <p className="rec-message">{rec.message}</p>
                            {rec.action && (
                              <p className="rec-action"><strong>Action:</strong> {rec.action}</p>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      <ScoringModelPanel token={token} />

      <AnalysisHistory history={history} loading={loadingHistory} onOpen={openPastAnalysis} />
    </div>
  );
};

export default DomainAnalysis;
