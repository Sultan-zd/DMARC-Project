import React, { useEffect, useState } from 'react';
import { SlidersHorizontal, ChevronDown, Server } from 'lucide-react';
import * as api from '../../services/api';
import './ScoringModelPanel.css';

/**
 * Renders what an outcome does to the score.
 *
 * An outcome that moves no points carries its own word for what happens instead —
 * printing it as "0" read as a full loss of the control's allowance.
 */
const effect = ({ points, label }) => {
  if (points === null || points === undefined) return { text: label ?? 'not scored', tone: 'muted' };
  if (points < 0) return { text: `${points}`, tone: 'bad' };
  if (points === 0) return { text: '0', tone: 'bad' };
  return { text: `+${points}`, tone: 'good' };
};

/**
 * The scoring rules, fetched from the engine that applies them.
 *
 * This panel replaced a hand-written description of the scale. That description had
 * drifted on eight values — it still credited BIMI with five points, put the grade
 * boundaries a band away from where they are, and never mentioned that an
 * unreadable DKIM selector is dropped from the ratio rather than scored as zero.
 * Nothing here is written twice: every figure arrives from /api/analysis/scoring-model.
 */
const ScoringModelPanel = ({ token }) => {
  const [model, setModel] = useState(null);
  const [open, setOpen] = useState(true);

  useEffect(() => {
    if (!token) return undefined;
    let cancelled = false;
    api.getScoringModel(token)
      .then((data) => !cancelled && setModel(data))
      .catch(() => { /* The panel is explanatory; its absence breaks nothing. */ });
    return () => { cancelled = true; };
  }, [token]);

  if (!model) return null;

  return (
    <section className={`scoring-model ${open ? 'open' : ''}`}>
      <button
        className="scoring-model-head"
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <SlidersHorizontal size={20} />
        <div>
          <h2>How the score is calculated</h2>
          <p>
            Four controls, {model.controls.filter((c) => c.maxPoints).reduce((s, c) => s + c.maxPoints, 0)} points,
            {' '}read live from {model.resolver}
          </p>
        </div>
        <ChevronDown size={20} className="scoring-model-chevron" />
      </button>

      {open && (
        <div className="scoring-model-body">
          <div className="control-grid">
            {model.controls.map((control) => (
              <article
                key={control.name}
                className={`control-card ${control.outcomes.length === 0 ? 'wide' : ''}`}
              >
                <header>
                  <h3>{control.name}</h3>
                  <span className={`control-weight ${control.maxPoints ? '' : 'unscored'}`}>
                    {control.maxPoints ? `${control.maxPoints} pts` : 'not scored'}
                  </span>
                </header>
                <p className="control-purpose">{control.purpose}</p>
                <p className="control-lookup"><Server size={12} /> {control.lookup}</p>

                {control.outcomes.length > 0 && (
                  <table className="outcome-table">
                    <tbody>
                      {control.outcomes.map((outcome) => {
                        const p = effect(outcome);
                        return (
                          <tr key={outcome.condition}>
                            <th scope="row">{outcome.condition}</th>
                            <td className={`outcome-points ${p.tone}`}>{p.text}</td>
                            <td className="outcome-meaning">{outcome.meaning}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
              </article>
            ))}
          </div>

          <div className="scoring-model-footer">
            <div className="grade-bands">
              <h3>Grades</h3>
              <ul>
                {model.grades.map((band) => (
                  <li key={band.grade}>
                    <span className={`grade-chip grade-${band.grade.replace('+', 'plus')}`}>
                      {band.grade}
                    </span>
                    <span className="grade-range">{band.min}–{band.max}</span>
                  </li>
                ))}
              </ul>
            </div>
            <p className="ratio-note">{model.ratioNote}</p>
          </div>
        </div>
      )}
    </section>
  );
};

export default ScoringModelPanel;
