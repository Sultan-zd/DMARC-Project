import React from 'react';
import { Globe, Gauge, ShieldCheck, ShieldAlert } from 'lucide-react';
import './AnalysisPosture.css';

/**
 * Where the team stands across everything it has analysed.
 *
 * Derived from the analysis history rather than counted server-side, so it always
 * describes the same rows the table below lists. Only the newest analysis of each
 * domain counts: re-checking one domain ten times must not make the estimate ten
 * times more confident about it.
 */
const summarise = (history) => {
  const latest = new Map();
  history.forEach((item) => {
    const seen = latest.get(item.domain);
    if (!seen || new Date(item.analyzed_at) > new Date(seen.analyzed_at)) {
      latest.set(item.domain, item);
    }
  });

  const current = [...latest.values()];
  if (current.length === 0) return null;

  const scores = current.map((a) => a.score?.score ?? 0);
  const sorted = [...current].sort((a, b) => (a.score?.score ?? 0) - (b.score?.score ?? 0));

  return {
    domains: current.length,
    analyses: history.length,
    average: Math.round(scores.reduce((s, n) => s + n, 0) / scores.length),
    weakest: sorted[0],
    strongest: sorted[sorted.length - 1],
    // Anything below the A band still leaves a gap worth closing.
    needingWork: current.filter((a) => (a.score?.score ?? 0) < 80).length,
  };
};

/** Matches the engine's own colour bands, so an A never shows as a warning. */
const tone = (score) => (score >= 80 ? 'good' : score >= 60 ? 'warn' : 'bad');

const AnalysisPosture = ({ history }) => {
  const s = summarise(history);
  if (!s) return null;

  return (
    <div className="analysis-posture">
      <div className="posture-tile">
        <Globe size={18} />
        <div>
          <span className="posture-value">{s.domains}</span>
          <span className="posture-label">
            {s.domains === 1 ? 'domain analysed' : 'domains analysed'}
          </span>
        </div>
        <span className="posture-foot">{s.analyses} checks run in total</span>
      </div>

      <div className="posture-tile">
        <Gauge size={18} />
        <div>
          <span className={`posture-value ${tone(s.average)}`}>{s.average}</span>
          <span className="posture-label">average score</span>
        </div>
        <span className="posture-foot">
          {s.needingWork === 0
            ? 'every domain is at A or better'
            : `${s.needingWork} below the A band`}
        </span>
      </div>

      <div className="posture-tile">
        <ShieldAlert size={18} />
        <div>
          <span className={`posture-value ${tone(s.weakest.score?.score ?? 0)}`}>
            {s.weakest.score?.grade ?? '—'}
          </span>
          <span className="posture-label">weakest domain</span>
        </div>
        <span className="posture-foot mono">{s.weakest.domain}</span>
      </div>

      <div className="posture-tile">
        <ShieldCheck size={18} />
        <div>
          <span className={`posture-value ${tone(s.strongest.score?.score ?? 0)}`}>
            {s.strongest.score?.grade ?? '—'}
          </span>
          <span className="posture-label">strongest domain</span>
        </div>
        <span className="posture-foot mono">{s.strongest.domain}</span>
      </div>
    </div>
  );
};

export default AnalysisPosture;
