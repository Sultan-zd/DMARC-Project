import React from 'react';
import { ArrowRight, TrendingUp, TrendingDown, Minus, Search } from 'lucide-react';
import SkeletonLoader from '../ui/SkeletonLoader';
import './AnalysisHistory.css';

/** Matches the engine's own colour bands, so an A never shows as a warning. */
const gradeTone = (score) => (score >= 80 ? 'good' : score >= 60 ? 'warn' : 'bad');

/**
 * Every check the team has run, newest first.
 *
 * Each row carries the movement since that domain was last analysed, which is the
 * part an aggregate score cannot show: a domain sitting at 70 having climbed from
 * 40 is a different situation from one that has just fallen there.
 */
const AnalysisHistory = ({ history, loading, onOpen }) => {
  // Walking oldest-first lets each row see the score that preceded it.
  const deltas = new Map();
  const previous = new Map();
  [...history].reverse().forEach((item) => {
    const before = previous.get(item.domain);
    if (before !== undefined) deltas.set(item.id, (item.score?.score ?? 0) - before);
    previous.set(item.domain, item.score?.score ?? 0);
  });

  if (loading) {
    return (
      <section className="analysis-history glass-card">
        <h3>Analysis history</h3>
        <SkeletonLoader type="table" lines={5} />
      </section>
    );
  }

  if (history.length === 0) {
    return (
      <section className="analysis-history glass-card">
        <h3>Analysis history</h3>
        <div className="history-empty">
          <Search size={28} />
          <p>Nothing analysed yet. Every check your team runs is kept here.</p>
        </div>
      </section>
    );
  }

  return (
    <section className="analysis-history glass-card">
      <div className="history-head">
        <h3>Analysis history</h3>
        <span className="history-count">
          {history.length} {history.length === 1 ? 'check' : 'checks'}
        </span>
      </div>

      <div className="table-responsive">
        <table className="history-table">
          <thead>
            <tr>
              <th>Domain</th>
              <th>Grade</th>
              <th>Score</th>
              <th>Change</th>
              <th>DMARC policy</th>
              <th>Run by</th>
              <th>When</th>
              <th><span className="sr-only">Open</span></th>
            </tr>
          </thead>
          <tbody>
            {history.map((item) => {
              const score = item.score?.score ?? 0;
              const delta = deltas.get(item.id);
              const dmarc = item.records?.find((r) => r.type === 'DMARC');
              const policy = dmarc?.parsed?.p;

              return (
                <tr key={item.id}>
                  <td className="font-mono domain-cell">{item.domain}</td>
                  <td>
                    <span className={`grade-pill ${gradeTone(score)}`}>
                      {item.score?.grade ?? '—'}
                    </span>
                  </td>
                  <td className="num">{score}</td>
                  <td>
                    {delta === undefined ? (
                      <span className="delta first">first check</span>
                    ) : delta === 0 ? (
                      <span className="delta flat"><Minus size={13} /> no change</span>
                    ) : delta > 0 ? (
                      <span className="delta up"><TrendingUp size={13} /> +{delta}</span>
                    ) : (
                      <span className="delta down"><TrendingDown size={13} /> {delta}</span>
                    )}
                  </td>
                  <td>
                    {policy ? (
                      <span className={`policy-pill p-${policy}`}>p={policy}</span>
                    ) : (
                      <span className="policy-pill none">no record</span>
                    )}
                  </td>
                  <td className="muted">{item.analyzed_by ?? '—'}</td>
                  <td className="muted">
                    {new Date(item.analyzed_at).toLocaleString('en-GB', {
                      day: '2-digit', month: 'short', year: 'numeric',
                      hour: '2-digit', minute: '2-digit',
                    })}
                  </td>
                  <td>
                    <button className="history-open" onClick={() => onOpen(item.id)}>
                      Open <ArrowRight size={14} />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </section>
  );
};

export default AnalysisHistory;
