import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Database, Search, Trash2, Eye, EyeOff, ChevronLeft, ChevronRight,
  Loader2, Lock, AlertTriangle, KeyRound, Link2, X, Table2,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import RowDetail from './RowDetail';
import { renderValue, formatBytes, count } from './databaseFormat';
import * as api from '../../services/api';
import './DatabaseConsole.css';

const PAGE_SIZE = 25;

/**
 * The schema, readable and editable from here.
 *
 * <p>Everything below is already reachable through a database client, so nothing
 * here is a new power. What is new is the reach: a client listens on localhost and
 * this page does not. Hence the password prompt in front of anything that destroys
 * data — a session left open on an unlocked machine should be worth reading, not
 * erasing.
 *
 * <p>And what a client cannot do: say what a table is for. Every column arrives
 * with its meaning, its type and its foreign key resolved to a name, because the
 * person reading this at two in the morning is not necessarily the person who wrote
 * the schema.
 */
const DatabaseConsole = () => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [tables, setTables] = useState(null);
  const [openTable, setOpenTable] = useState(null);
  const [data, setData] = useState(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const [applied, setApplied] = useState('');
  const [reveal, setReveal] = useState(false);
  const [loading, setLoading] = useState(false);
  const [detailRow, setDetailRow] = useState(null);

  // { kind: 'row' | 'table', table, key, rows } — what the password will confirm.
  const [pending, setPending] = useState(null);
  const [password, setPassword] = useState('');
  const [typedName, setTypedName] = useState('');
  const [working, setWorking] = useState(false);

  const loadTables = useCallback(async () => {
    try {
      setTables(await api.getDatabaseTables(token));
    } catch (err) {
      showToast(err.message, 'error');
    }
  }, [token, showToast]);

  useEffect(() => { if (token) loadTables(); }, [token, loadTables]);

  const loadRows = useCallback(async (table, nextPage, term, revealed) => {
    setLoading(true);
    try {
      setData(await api.getDatabaseRows(token, table, {
        page: nextPage, page_size: PAGE_SIZE, search: term || undefined, reveal: revealed,
      }));
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [token, showToast]);

  const open = (table) => {
    setOpenTable(table);
    setDetailRow(null);
    setPage(1);
    setSearch('');
    setApplied('');
    setReveal(false);
    loadRows(table.name, 1, '', false);
  };

  const goToPage = (next) => {
    setPage(next);
    setDetailRow(null);
    loadRows(data.table, next, applied, reveal);
  };

  const runSearch = () => {
    setPage(1);
    setApplied(search);
    setDetailRow(null);
    loadRows(data.table, 1, search, reveal);
  };

  const toggleReveal = () => {
    const next = !reveal;
    setReveal(next);
    loadRows(data.table, page, applied, next);
  };

  const confirm = async () => {
    setWorking(true);
    try {
      if (pending.kind === 'row') {
        await api.deleteDatabaseRow(token, pending.table, pending.key, password);
        showToast('Record deleted', 'success');
      } else {
        const { detail } = await api.clearDatabaseTable(token, pending.table, password);
        showToast(detail, 'success');
      }
      setPending(null);
      setPassword('');
      setTypedName('');
      setDetailRow(null);
      await loadTables();
      if (openTable) await loadRows(openTable.name, page, applied, reveal);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setWorking(false);
    }
  };

  /** Eleven tables read as three subjects once they are grouped by what they hold. */
  const grouped = useMemo(() => {
    if (!tables) return [];
    const groups = new Map();
    tables.forEach((t) => {
      if (!groups.has(t.group)) groups.set(t.group, []);
      groups.get(t.group).push(t);
    });
    return [...groups.entries()].map(([name, items]) => ({ name, items }));
  }, [tables]);

  const totals = useMemo(() => ({
    rows: (tables ?? []).reduce((sum, t) => sum + t.rows, 0),
    sizeKb: (tables ?? []).reduce((sum, t) => sum + t.sizeKb, 0),
  }), [tables]);

  if (!tables) return null;

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;
  const firstOnPage = data ? (data.page - 1) * data.pageSize + 1 : 0;
  const lastOnPage = data ? Math.min(data.page * data.pageSize, data.total) : 0;

  return (
    <section className="platform-card database-console">
      <header className="db-head">
        <div>
          <h2><Database size={17} /> Database</h2>
          <p className="db-lead">
            Every table in this schema, described. The same reach a database client
            gives you, with a password asked for anything that removes data.
          </p>
        </div>
        <dl className="db-totals">
          <div><dt>Tables</dt><dd>{tables.length}</dd></div>
          <div><dt>Records</dt><dd>{count(totals.rows)}</dd></div>
          <div><dt>On disk</dt><dd>{formatBytes(totals.sizeKb)}</dd></div>
        </dl>
      </header>

      <div className="db-layout">
        {/* ── Which table ── */}
        <nav className="db-rail" aria-label="Tables">
          {grouped.map((group) => (
            <div className="db-rail-group" key={group.name}>
              <h3>{group.name}</h3>
              <ul>
                {group.items.map((t) => (
                  <li key={t.name}>
                    <button
                      className={`db-rail-item ${openTable?.name === t.name ? 'active' : ''}`}
                      onClick={() => open(t)}
                      title={t.description}
                    >
                      <span className="db-rail-label">
                        {t.label}
                        {t.protectedTable && (
                          <Lock size={10} aria-label="Cannot be emptied in one action" />
                        )}
                      </span>
                      <span className="db-rail-name">{t.name}</span>
                      <span className="db-rail-count">{count(t.rows)}</span>
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        {/* ── That table ── */}
        <div className="db-main">
          {!data ? (
            <div className="db-placeholder">
              <Table2 size={30} />
              <p>Choose a table to read it.</p>
              <span>
                Each one arrives with what it holds, what every column means, and its
                links to the others resolved to names.
              </span>
            </div>
          ) : (
            <>
              <div className="db-table-head">
                <div className="db-table-title">
                  <h3>{data.label}</h3>
                  <code>{data.table}</code>
                  {openTable?.protectedTable && (
                    <span className="db-protected" title="Emptying it in one action is refused">
                      <Lock size={11} /> protected
                    </span>
                  )}
                </div>
                {data.description && <p className="db-table-note">{data.description}</p>}
                <p className="db-table-facts">
                  {count(data.total)} {data.total === 1 ? 'record' : 'records'}
                  {openTable?.sizeKb > 0 && ` · ${formatBytes(openTable.sizeKb)} on disk`}
                  {` · ${data.columns.length} columns`}
                  {data.primaryKey && <> · keyed on <code>{data.primaryKey}</code></>}
                </p>
              </div>

              <div className="db-toolbar">
                <div className="db-search">
                  <Search size={15} />
                  <input
                    type="text"
                    value={search}
                    placeholder={`Search every column of ${data.table}…`}
                    onChange={(e) => setSearch(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') runSearch(); }}
                  />
                  {applied && (
                    <button
                      className="db-search-clear"
                      onClick={() => { setSearch(''); setApplied(''); setPage(1); loadRows(data.table, 1, '', reveal); }}
                      aria-label="Clear search"
                    >
                      <X size={13} />
                    </button>
                  )}
                </div>

                {data.maskedColumns.length > 0 && (
                  <button
                    className={`db-reveal ${reveal ? 'on' : ''}`}
                    onClick={toggleReveal}
                    title={data.maskedColumns.join(', ')}
                  >
                    {reveal ? <EyeOff size={14} /> : <Eye size={14} />}
                    {reveal ? 'Hide' : 'Show'} {data.maskedColumns.length} secret
                    {data.maskedColumns.length === 1 ? '' : 's'}
                  </button>
                )}

                {!openTable?.protectedTable && data.total > 0 && (
                  <button
                    className="btn-text-danger"
                    onClick={() => setPending({ kind: 'table', table: data.table, rows: data.total })}
                  >
                    <Trash2 size={14} /> Empty table
                  </button>
                )}
              </div>

              {reveal && (
                <p className="db-revealed">
                  <AlertTriangle size={13} />
                  Secret columns are in clear on this page. The reveal was logged with
                  your name.
                </p>
              )}

              {applied && (
                <p className="db-filtered">
                  Filtered on <strong>{applied}</strong> — {count(data.total)} matching
                  {data.total === 1 ? ' record' : ' records'}.
                </p>
              )}

              <div className="db-grid-wrap">
                <table className="db-rows">
                  <thead>
                    <tr>
                      {data.columns.map((c) => (
                        <th
                          key={c.name}
                          className={c.credential ? 'masked' : ''}
                          title={c.description || c.name}
                        >
                          <span className="db-col-label">
                            {c.primaryKey && <KeyRound size={9} />}
                            {c.referencesTable && <Link2 size={9} />}
                            {c.credential && <Lock size={9} />}
                            {c.label}
                          </span>
                          <span className="db-col-name">{c.name}</span>
                        </th>
                      ))}
                      <th className="db-actions-head" aria-label="Actions" />
                    </tr>
                  </thead>
                  <tbody>
                    {loading ? (
                      <tr><td colSpan={data.columns.length + 1} className="db-loading">
                        <Loader2 size={16} className="spin" /> Reading…
                      </td></tr>
                    ) : data.rows.length === 0 ? (
                      <tr><td colSpan={data.columns.length + 1} className="db-loading">
                        {applied ? 'Nothing matches that search.' : 'This table is empty.'}
                      </td></tr>
                    ) : data.rows.map((row) => {
                      const key = data.primaryKey ? String(row[data.primaryKey]) : null;
                      return (
                        <tr
                          key={key ?? JSON.stringify(row)}
                          className={detailRow === row ? 'open' : ''}
                          onClick={() => setDetailRow(row)}
                        >
                          {data.columns.map((c) => (
                            <td key={c.name}>{renderValue(row[c.name], c, data.references)}</td>
                          ))}
                          <td className="db-row-actions">
                            {key !== null && (
                              <button
                                className="icon-btn danger"
                                title="Delete this record"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setPending({ kind: 'row', table: data.table, key });
                                }}
                              >
                                <Trash2 size={14} />
                              </button>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>

              {data.total > 0 && (
                <div className="db-pager">
                  <span className="db-pager-range">
                    {count(firstOnPage)}–{count(lastOnPage)} of {count(data.total)}
                  </span>
                  <div className="db-pager-buttons">
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
            </>
          )}
        </div>
      </div>

      {detailRow && data && (
        <RowDetail
          page={data}
          row={detailRow}
          onClose={() => setDetailRow(null)}
          onDelete={(key) => setPending({ kind: 'row', table: data.table, key })}
        />
      )}

      {/* Password stands between a live session and irreversible loss. */}
      {pending && (
        <div className="db-confirm-backdrop" onClick={() => !working && setPending(null)}>
          <div className="db-confirm" onClick={(e) => e.stopPropagation()}>
            <h3><AlertTriangle size={17} /> This cannot be undone</h3>

            {pending.kind === 'row' ? (
              <p>
                Deleting one record from <code>{pending.table}</code>. Anything that
                referenced it goes with it.
              </p>
            ) : (
              <>
                <p>
                  Emptying <code>{pending.table}</code> removes{' '}
                  <strong>{count(pending.rows)} records</strong>. Type the table name to
                  confirm you mean this one.
                </p>
                <input
                  type="text"
                  className="db-confirm-name"
                  value={typedName}
                  placeholder={pending.table}
                  onChange={(e) => setTypedName(e.target.value)}
                />
              </>
            )}

            <label className="db-confirm-field">
              <span>Your password</span>
              <input
                type="password"
                value={password}
                autoComplete="current-password"
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter' && password) confirm(); }}
                autoFocus
              />
            </label>

            <div className="db-confirm-actions">
              <button
                className="btn btn-danger"
                disabled={working || !password
                  || (pending.kind === 'table' && typedName !== pending.table)}
                onClick={confirm}
              >
                {working ? <Loader2 size={15} className="spin" /> : 'Delete'}
              </button>
              <button
                className="btn btn-secondary"
                disabled={working}
                onClick={() => { setPending(null); setPassword(''); setTypedName(''); }}
              >
                Cancel
              </button>
            </div>
          </div>
        </div>
      )}
    </section>
  );
};

export default DatabaseConsole;
