import React, { useCallback, useEffect, useState } from 'react';
import {
  ScrollText, Search, ChevronLeft, ChevronRight, Loader2, X, Filter,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { formatStamp, relative, count } from './databaseFormat';
import * as api from '../../services/api';
import './AuditTrail.css';

const PAGE_SIZE = 25;

/**
 * How loud each action is.
 *
 * <p>Not severity — nothing here is an error. It is how much a reader should slow
 * down: emptying a table and revealing credential columns are the two that cannot
 * be undone or taken back, and they should not look like an account being enabled.
 */
const WEIGHT = {
  DATABASE_TABLE_CLEARED: 'severe',
  DATABASE_SECRETS_REVEALED: 'severe',
  ACCOUNT_DELETED: 'severe',
  ORGANIZATION_REMOVED: 'severe',
  DATABASE_ROW_DELETED: 'notable',
  ACCOUNT_DISABLED: 'notable',
  SESSIONS_REVOKED: 'notable',
  ACCOUNT_ROLE_CHANGED: 'notable',
  ACCOUNT_PASSWORD_RESET_BY_ADMIN: 'notable',
  SIGN_IN_FAILED: 'notable',
};

/** ACCOUNT_ROLE_CHANGED reads as "Account role changed" without a lookup table. */
const readable = (action) => {
  const words = String(action).toLowerCase().split('_');
  return words[0].charAt(0).toUpperCase() + words[0].slice(1)
    + (words.length > 1 ? ' ' + words.slice(1).join(' ') : '');
};

/**
 * Who did what, and when.
 *
 * <p>All of this was already in the application log. What a table adds is the
 * ability to ask: by actor, by kind, over a period. "Who deleted this account in
 * March" was a search through rotated files, and only if the files still existed.
 */
const AuditTrail = () => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(1);
  const [actor, setActor] = useState('');
  const [action, setAction] = useState('');
  const [days, setDays] = useState('');

  const load = useCallback(async (nextPage, filters) => {
    setLoading(true);
    try {
      setData(await api.getAuditTrail(token, {
        page: nextPage, page_size: PAGE_SIZE, ...filters,
      }));
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [token, showToast]);

  useEffect(() => { if (token) load(1, {}); }, [token, load]);

  const applyFilters = (next = {}) => {
    const filters = { actor, action, days, ...next };
    setActor(filters.actor);
    setAction(filters.action);
    setDays(filters.days);
    setPage(1);
    load(1, filters);
  };

  const clear = () => {
    setActor(''); setAction(''); setDays(''); setPage(1);
    load(1, {});
  };

  const goToPage = (next) => {
    setPage(next);
    load(next, { actor, action, days });
  };

  if (!data) return null;

  const totalPages = Math.max(1, Math.ceil(data.total / data.pageSize));
  const filtered = actor || action || days;

  return (
    <section className="platform-card audit-trail">
      <header className="audit-head">
        <div>
          <h2><ScrollText size={17} /> Audit trail</h2>
          <p className="audit-lead">
            Every action that changed something, with who did it. Written once and
            never updated — the application log has the same lines, but it rotates
            and cannot be asked a question.
          </p>
        </div>
        <div className="audit-total">
          <span>Recorded</span>
          <strong>{count(data.total)}</strong>
        </div>
      </header>

      <div className="audit-filters">
        <div className="audit-search">
          <Search size={15} />
          <input
            type="text"
            value={actor}
            placeholder="Filter by who did it…"
            onChange={(e) => setActor(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter') applyFilters(); }}
          />
        </div>

        <select value={action} onChange={(e) => applyFilters({ action: e.target.value })}>
          <option value="">Any action</option>
          {data.actions.map((a) => (
            <option key={a} value={a}>{readable(a)}</option>
          ))}
        </select>

        <select value={days} onChange={(e) => applyFilters({ days: e.target.value })}>
          <option value="">All time</option>
          <option value="1">Last 24 hours</option>
          <option value="7">Last 7 days</option>
          <option value="30">Last 30 days</option>
          <option value="90">Last 90 days</option>
        </select>

        <button className="btn btn-secondary" onClick={() => applyFilters()}>
          <Filter size={14} /> Apply
        </button>

        {filtered && (
          <button className="audit-clear" onClick={clear}>
            <X size={13} /> Clear
          </button>
        )}
      </div>

      <div className="audit-list-wrap">
        {loading ? (
          <p className="audit-empty"><Loader2 size={16} className="spin" /> Reading…</p>
        ) : data.entries.length === 0 ? (
          <p className="audit-empty">
            {filtered
              ? 'Nothing matches those filters.'
              : 'Nothing recorded yet. Entries appear as accounts, organizations and '
                + 'the database are changed.'}
          </p>
        ) : (
          <ol className="audit-list">
            {data.entries.map((e) => (
              <li key={e.id} className={WEIGHT[e.action] || 'routine'}>
                <div className="audit-when">
                  <span className="audit-stamp">{formatStamp(e.at)}</span>
                  <span className="audit-ago">{relative(e.at)}</span>
                </div>

                <div className="audit-what">
                  <div className="audit-line">
                    <strong className="audit-actor">{e.actor}</strong>
                    <span className={`audit-action ${WEIGHT[e.action] || 'routine'}`}>
                      {readable(e.action)}
                    </span>
                    {e.targetLabel && (
                      <span className="audit-target">
                        {e.targetType && <span className="audit-target-kind">{e.targetType}</span>}
                        {e.targetLabel}
                        {e.targetId != null && <span className="audit-target-id">#{e.targetId}</span>}
                      </span>
                    )}
                  </div>
                  {e.detail && <p className="audit-detail">{e.detail}</p>}
                </div>

                <span className="audit-ip">{e.clientIp || '—'}</span>
              </li>
            ))}
          </ol>
        )}
      </div>

      {data.total > 0 && (
        <div className="audit-pager">
          <span>
            {count((data.page - 1) * data.pageSize + 1)}–
            {count(Math.min(data.page * data.pageSize, data.total))} of {count(data.total)}
          </span>
          <div className="audit-pager-buttons">
            <button disabled={page <= 1} onClick={() => goToPage(page - 1)}
                    aria-label="Previous page">
              <ChevronLeft size={15} />
            </button>
            <span>Page {page} of {totalPages}</span>
            <button disabled={page >= totalPages} onClick={() => goToPage(page + 1)}
                    aria-label="Next page">
              <ChevronRight size={15} />
            </button>
          </div>
        </div>
      )}
    </section>
  );
};

export default AuditTrail;
