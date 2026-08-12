import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Search, ShieldCheck, FileSearch, Gauge, Loader2, AlertTriangle,
  Mail, Eye, Lock, ArrowRight, CheckCircle2, Ban, ShieldAlert,
} from 'lucide-react';
import PublicNav from '../components/layout/PublicNav';
import usePageTitle from '../hooks/usePageTitle';
import './Landing.css';

/**
 * Public entry point.
 *
 * The page teaches before it asks: someone who does not yet know what DMARC is
 * cannot evaluate a DMARC product. Each section answers one question, in the order
 * a newcomer actually asks it — what is broken, why now, what do I do, what does
 * this tool give me.
 *
 * Note on social proof: there is deliberately none. Testimonials and customer logos
 * belong here only once they are real.
 */
const Landing = () => {
  // Kept identical to the <title> in index.html. This is the page search engines
  // rank, and letting React replace a title written for a search result with one
  // assembled from the product name would undo that on render.
  usePageTitle(
    'DMARC Checker — Free SPF, DKIM & DMARC Record Lookup | Teknologiia',
    { absolute: true },
  );
  const navigate = useNavigate();
  const [domain, setDomain] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = (event) => {
    event.preventDefault();
    const value = domain.trim();
    if (!value) return;

    setSubmitting(true);
    const slug = value.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '').split('/')[0];
    navigate(`/scan/${encodeURIComponent(slug)}`);
  };

  return (
    <div className="landing">
      <PublicNav />

      {/* ── Hero ─────────────────────────────────────────────── */}
      <main className="landing-hero" id="scan">
        <div className="landing-hero-inner">
          <p className="landing-eyebrow">Email Security Platform</p>
          <h1>
            Anyone can send email as your domain.
            <span className="landing-highlight"> Find out who does.</span>
          </h1>
          <p className="landing-sub">
            DMARC tells receiving mail servers what to do with messages that forge your
            name — and reports back who tried. Check any domain in seconds, free.
          </p>

          <form className="landing-form" onSubmit={handleSubmit}>
            <div className="landing-input-wrap">
              <Search size={20} className="landing-input-icon" />
              <input
                type="text"
                value={domain}
                onChange={(event) => setDomain(event.target.value)}
                placeholder="yourcompany.com"
                aria-label="Domain to analyse"
                autoComplete="off"
                autoCapitalize="off"
                spellCheck="false"
              />
            </div>
            <button type="submit" className="landing-submit" disabled={!domain.trim() || submitting}>
              {submitting ? <Loader2 size={18} className="spin" /> : null}
              {submitting ? 'Analysing…' : 'Analyse domain'}
            </button>
          </form>

          <p className="landing-note">
            No account, no email address. We only read public DNS records — nothing is
            sent to the domain itself.
          </p>

          <ul className="landing-pains">
            <li><Ban size={16} /> Mail rejected by Gmail</li>
            <li><ShieldAlert size={16} /> Someone spoofing your brand</li>
            <li><Eye size={16} /> No idea who sends as you</li>
            <li><AlertTriangle size={16} /> Failing a compliance audit</li>
          </ul>
        </div>
      </main>

      {/* ── The problem ──────────────────────────────────────── */}
      <section className="landing-section" id="problem">
        <div className="landing-container">
          <p className="section-eyebrow">The problem</p>
          <h2>Email was built without a way to prove who sent it</h2>
          <p className="section-lead">
            The “From” line on an email is just text. Nothing in the original protocol
            checks it, which is why an attacker can put your domain there and a
            recipient sees a message that looks like it came from you.
          </p>

          <div className="problem-grid">
            <article>
              <span className="problem-step">SPF</span>
              <h3>Which servers may send</h3>
              <p>
                A DNS record listing the servers allowed to send for your domain.
                It breaks when mail is forwarded, so it cannot stand alone.
              </p>
            </article>
            <article>
              <span className="problem-step">DKIM</span>
              <h3>A signature that survives forwarding</h3>
              <p>
                Outgoing mail is signed with a private key; receivers verify it against
                a public key in your DNS. Proves the message was not altered.
              </p>
            </article>
            <article>
              <span className="problem-step">DMARC</span>
              <h3>What to do when both fail</h3>
              <p>
                Ties the two to the visible “From” address, tells receivers to
                quarantine or reject impostors, and asks them to report what they saw.
              </p>
            </article>
          </div>
        </div>
      </section>

      {/* ── Why now ──────────────────────────────────────────── */}
      <section className="landing-section landing-section-dark" id="compliance">
        <div className="landing-container">
          <p className="section-eyebrow">Why now</p>
          <h2>The largest mailbox providers now require it</h2>
          <p className="section-lead">
            DMARC stopped being optional for anyone sending in volume. Without it,
            legitimate mail is throttled or refused outright.
          </p>

          <div className="compliance-grid">
            <article>
              <h3>Google &amp; Yahoo</h3>
              <p className="compliance-date">Since February 2024</p>
              <p>
                Bulk senders must publish DMARC, authenticate with SPF and DKIM, and
                keep spam complaints low. Non-compliant mail is rejected.
              </p>
            </article>
            <article>
              <h3>Microsoft Outlook</h3>
              <p className="compliance-date">Since May 2025</p>
              <p>
                The same requirements extended to Outlook.com and Hotmail domains for
                high-volume senders.
              </p>
            </article>
            <article>
              <h3>Auditors and insurers</h3>
              <p className="compliance-date">Ongoing</p>
              <p>
                Email authentication appears in most security questionnaires and cyber
                insurance reviews. “p=none” rarely satisfies them.
              </p>
            </article>
          </div>
        </div>
      </section>

      {/* ── How it works ─────────────────────────────────────── */}
      <section className="landing-section" id="how">
        <div className="landing-container">
          <p className="section-eyebrow">How it works</p>
          <h2>From unprotected to enforcing, without blocking your own mail</h2>
          <p className="section-lead">
            Going straight to enforcement is how companies accidentally block their own
            invoices. The safe path is to watch first, fix what you find, then tighten.
          </p>

          <ol className="how-steps">
            <li>
              <span className="how-number">1</span>
              <div>
                <h3>Check where you stand</h3>
                <p>
                  Analyse your domain above. You get a graded report of DMARC, SPF, DKIM
                  and MX, with the exact record to publish for anything missing.
                </p>
              </div>
            </li>
            <li>
              <span className="how-number">2</span>
              <div>
                <h3>Start collecting reports</h3>
                <p>
                  Publish a DMARC record with a reporting address. Mailbox providers
                  begin sending daily XML reports of everything sent as your domain.
                </p>
              </div>
            </li>
            <li>
              <span className="how-number">3</span>
              <div>
                <h3>See who is actually sending</h3>
                <p>
                  Upload those reports here. The dashboard shows every source — your
                  marketing tool, your invoicing system, and anyone impersonating you.
                </p>
              </div>
            </li>
            <li>
              <span className="how-number">4</span>
              <div>
                <h3>Move to enforcement</h3>
                <p>
                  Once every legitimate sender authenticates, raise the policy to
                  quarantine and then reject. Impostors stop reaching inboxes.
                </p>
              </div>
            </li>
          </ol>
        </div>
      </section>

      {/* ── Policy ladder ────────────────────────────────────── */}
      <section className="landing-section landing-section-tint">
        <div className="landing-container">
          <p className="section-eyebrow">The three policies</p>
          <h2>Only one of them actually stops anything</h2>

          <div className="ladder">
            <article className="ladder-step tone-bad">
              <code>p=none</code>
              <h3>Monitor</h3>
              <p>Nothing is blocked. You only receive reports. A starting point, not a destination.</p>
            </article>
            <ArrowRight className="ladder-arrow" size={20} aria-hidden="true" />
            <article className="ladder-step tone-warn">
              <code>p=quarantine</code>
              <h3>Spam folder</h3>
              <p>Forged mail is delivered to spam. Better, but the message still reaches the recipient.</p>
            </article>
            <ArrowRight className="ladder-arrow" size={20} aria-hidden="true" />
            <article className="ladder-step tone-good">
              <code>p=reject</code>
              <h3>Blocked</h3>
              <p>Forged mail is refused at the door and never delivered. This is the goal.</p>
            </article>
          </div>

          <p className="ladder-note">
            Most domains that “have DMARC” are sitting on <code>p=none</code> and are
            still fully spoofable. The analyser tells you which one you are on.
          </p>
        </div>
      </section>

      {/* ── What you get ─────────────────────────────────────── */}
      <section className="landing-section" id="features">
        <div className="landing-container">
          <p className="section-eyebrow">The platform</p>
          <h2>Built for the whole deployment, not just the check</h2>

          <div className="landing-features-inner">
            <article>
              <ShieldCheck size={22} />
              <h3>Five checks, one score</h3>
              <p>
                DMARC policy, SPF reach and lookup budget, DKIM key strength, MX routing
                and BIMI are each graded, then combined into an A–F rating.
              </p>
            </article>
            <article>
              <FileSearch size={22} />
              <h3>Fixes, not just findings</h3>
              <p>
                Every gap comes with the specific DNS record to publish, so the report is
                something an administrator can act on directly.
              </p>
            </article>
            <article>
              <Mail size={22} />
              <h3>Report ingestion</h3>
              <p>
                Drop the <code>.xml</code>, <code>.gz</code> or <code>.zip</code> reports
                your providers send, or point the platform at the mailbox receiving them.
              </p>
            </article>
            <article>
              <Gauge size={22} />
              <h3>Posture over time</h3>
              <p>
                Track every domain you own in one view, weakest first, and see whether a
                change improved or regressed the score.
              </p>
            </article>
            <article>
              <Lock size={22} />
              <h3>Separated by organisation</h3>
              <p>
                Each account’s reports, alerts and analyses are isolated. Colleagues share
                a workspace; nobody else sees your data.
              </p>
            </article>
            <article>
              <CheckCircle2 size={22} />
              <h3>Reports you can hand over</h3>
              <p>
                Export a branded PDF summary for management, or the full record-level CSV
                for your own analysis.
              </p>
            </article>
          </div>
        </div>
      </section>

      {/* ── FAQ ──────────────────────────────────────────────── */}
      <section className="landing-section landing-section-tint" id="faq">
        <div className="landing-container landing-container-narrow">
          <p className="section-eyebrow">Questions</p>
          <h2>Frequently asked</h2>

          <div className="faq">
            <details>
              <summary>Does analysing a domain send it any email?</summary>
              <p>
                No. The analyser reads public DNS records only — the same records any
                mail server reads. Nothing is delivered to the domain, and the owner is
                not notified.
              </p>
            </details>
            <details>
              <summary>Can I check a domain I do not own?</summary>
              <p>
                Yes. DNS records are public, so anyone can inspect anyone’s configuration.
                Ingesting DMARC <em>reports</em> is different: those are sent only to the
                address the domain owner published, so you need control of the domain.
              </p>
            </details>
            <details>
              <summary>Why does my domain score well but still show failures?</summary>
              <p>
                Those measure different things. The score describes how your DNS is
                configured; the dashboard describes mail that was actually sent. A perfect
                configuration still shows failures if a legitimate service — a newsletter
                tool, say — is missing from your SPF record.
              </p>
            </details>
            <details>
              <summary>Why can DKIM sometimes not be verified?</summary>
              <p>
                DKIM keys live under a selector name, and DNS provides no way to list
                selectors — you can only test names and see which resolve. When none of the
                common names match, we say so rather than claiming DKIM is missing. Import
                your DMARC reports and the selectors in real use are checked directly.
              </p>
            </details>
            <details>
              <summary>Is the free analyser limited?</summary>
              <p>
                It is rate limited per address to keep it available, and results are cached
                briefly. Everything else — the graded report, the recommendations and the
                shareable link — is complete.
              </p>
            </details>
          </div>
        </div>
      </section>

      {/* ── Closing call to action ───────────────────────────── */}
      <section className="landing-cta">
        <div className="landing-container landing-container-narrow">
          <h2>See who is sending mail as your domain</h2>
          <p>
            Start with a free analysis. Create an account when you are ready to ingest
            reports and track posture over time.
          </p>
          <div className="landing-cta-actions">
            <a href="#scan" className="landing-submit">Analyse my domain</a>
            <Link to="/register" className="landing-cta-secondary">Create an account</Link>
          </div>
        </div>
      </section>

      {/* ── Footer ───────────────────────────────────────────── */}
      <footer className="landing-footer">
        <div className="landing-container landing-footer-grid">
          <div className="landing-footer-brand">
            <img src="/brand/logo-teknologiia.png" alt="Teknologiia" width="576" height="64" />
            <p>
              Email security platform. DMARC monitoring, domain analysis and report
              processing.
            </p>
          </div>

          <div>
            <h4>Product</h4>
            <a href="#features">Platform</a>
            <a href="#how">How it works</a>
            <Link to="/login">Sign in</Link>
            <Link to="/register">Create an account</Link>
          </div>

          <div>
            <h4>Free tools</h4>
            <a href="#scan">Domain analyser</a>
            <a href="#problem">SPF, DKIM &amp; DMARC explained</a>
            <a href="#faq">FAQ</a>
          </div>

          <div>
            <h4>Learn</h4>
            <a href="#compliance">Provider requirements</a>
            <a href="#problem">Why email is forgeable</a>
            <a href="#faq">Policy ladder</a>
          </div>
        </div>

        <div className="landing-container landing-footer-bottom">
          <span className="landing-footer-bars" aria-hidden="true" />
          <span>© {new Date().getFullYear()} Teknologiia — Email Security Platform</span>
        </div>
      </footer>
    </div>
  );
};

export default Landing;
