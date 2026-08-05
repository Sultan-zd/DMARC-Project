import React from 'react';
import { ShieldX, ShieldAlert, ShieldCheck } from 'lucide-react';
import './ScanVerdict.css';

/**
 * The plain-language answer, stated before any number.
 *
 * A score tells you how well a domain did; it does not tell you whether mail can
 * be forged in your name today. That is the question someone runs this check to
 * answer, so it is the first thing on the page — derived from the published DMARC
 * policy, which is what receivers actually act on.
 */
const verdictFor = (policy, pct) => {
  const partial = pct != null && pct < 100;

  switch (policy) {
    case 'reject':
      return partial
        ? {
            tone: 'warn', Icon: ShieldAlert,
            title: 'Partly protected',
            line: `Forged mail is rejected, but the policy is applied to only ${pct}% of messages.`,
            action: 'Raise pct to 100 to cover all mail.',
          }
        : {
            tone: 'good', Icon: ShieldCheck,
            title: 'Protected',
            line: 'Mail that forges this domain is rejected before it reaches an inbox.',
            action: null,
          };

    case 'quarantine':
      return {
        tone: 'warn', Icon: ShieldAlert,
        title: 'Partly protected',
        line: 'Forged mail is sent to the spam folder rather than blocked — it still reaches the recipient.',
        action: 'Move to p=reject once every legitimate sender authenticates.',
      };

    case 'none':
      return {
        tone: 'bad', Icon: ShieldX,
        title: 'Not protected',
        line: 'A DMARC record exists, but it blocks nothing. Anyone can still send mail as this domain.',
        action: 'Collect reports, then raise the policy to quarantine and reject.',
      };

    default:
      return {
        tone: 'bad', Icon: ShieldX,
        title: 'Not protected',
        line: 'No DMARC record is published. Anyone can send email that appears to come from this domain.',
        action: 'Publish a DMARC record to start receiving reports.',
      };
  }
};

const ScanVerdict = ({ domain, policy, pct, score, grade }) => {
  const { tone, Icon, title, line, action } = verdictFor(policy, pct);

  return (
    <section className={`verdict verdict-${tone}`} aria-live="polite">
      <div className="verdict-icon">
        <Icon size={30} />
      </div>

      <div className="verdict-body">
        <h2>
          <span className="verdict-domain">{domain}</span> — {title}
        </h2>
        <p className="verdict-line">{line}</p>
        {action && <p className="verdict-action">{action}</p>}
      </div>

      <div className="verdict-score" title="Configuration score across DMARC, SPF, DKIM and MX">
        <span className="verdict-grade">{grade}</span>
        <span className="verdict-number">{score}<small>/100</small></span>
      </div>
    </section>
  );
};

export default ScanVerdict;
