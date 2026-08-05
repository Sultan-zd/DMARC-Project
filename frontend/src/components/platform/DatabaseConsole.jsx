import React, { useCallback, useEffect, useState } from 'react';
import {
  Database, Search, Trash2, Eye, EyeOff, ChevronLeft, ChevronRight,
  Loader2, Lock, AlertTriangle,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import * as api from '../../services/api';
import './DatabaseConsole.css';

const number = (value) => new Intl.NumberFormat('en-GB').format(value ?? 0);

const cell = (value) => {
  if (value === null || value === undefined) return <span className="db-null">null</span>;
  if (value === true) return 'true';
  if (value === false) return 'false';
  const text = String(value);
  return text.length > 90 ? <span title={text}>{text.slice(0, 90)}…</span> : text;
};

/**
 * The schema, readable and editable from here.
 *
 * <p>Everything below is already reachable through a database client, so nothing
 * here is a new power. What is new is the reach: a client listens on localhost and
 * this page does not. Hence the password prompt in front of anything that destroys
 * data — a session left open on an unlocked machine should be worth reading, not
 * erasing.
 */
const DatabaseConsole = () => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [tables, setTables] = useState(null);
  const [openTable, setOpenTable] = useState(null);
  const [data, setData] = useState(null);
  const [page, setPage] = useState(1);
  const [search, setSearch] = useState('');
  const [reveal, setReveal] = useState(false);
  const [loading, setLoading] = useState(false);

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
        page: nextPage, page_size: 25, search: term || undefined, reveal: revealed,
      }));
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  }, [token, showToast]);

  const open = (table) => {
    setOpenTable(table);
    setPage(1);
    setSearch('');
    setReveal(false);
    loadRows(table.name, 1, '', false);
  };

  const confirm = async () => {
    setWorking(true);
    try {
      if (pending.kind === 'row') {
        await api.deleteDatabaseRow(token, pending.table, pending.key, password);
        showToast('Row deleted', 'success');
      } else {
        const { detail } = await api.clearDatabaseTable(token, pending.table, password);
        showToast(detail, 'success');
      }
      setPending(null);
      setPassword('');
      setTypedName('');
      await loadTables();
      if (openTable) await loadRows(openTable.name, page, search, reveal);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setWorking(false);
    }
  };

  if (!tables) return null;

  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.pageSize)) : 1;

  return (
    <section className="platform-card database-console">
      <h2><Database size={16} /> Database</h2>
      <p className="db-lead">
        Every table in this schema. The same view a database client gives you, with
        a password asked for anything that removes data.
      </p>

      <div className="db-tables">
        {tables.map((t) => (
          <button
            key={t.name}
            className={`db-table-chip ${openTable?.name === t.name ? 'active' : ''}`}
            onClick={() => open(t)}
          >
            <span className="db-table-name">{t.name}</span>
            <span className="db-table-rows">{number(t.rows)}</span>
            {t.protectedTable && <Lock size={11} title="Cannot be emptied in one action" />}
          </button>
        ))}
      </div>

      {openTable && data && (
        <div className="db-panel">
          <div className="db-toolbar">
            <div className="db-search">
              <Search size={15} />
              <input
                type="text"
                value={search}
                placeholder={`Search ${data.table}…`}
                onChange={(e) => setSearch(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') { setPage(1); loadRows(data.table, 1, search, reveal); }
                }}
              />
            </div>

            {data.maskedColumns.length > 0 && (
              <button
                className="db-reveal"
                onClick={() => {
                  const next = !reveal;
                  setReveal(next);
                  loadRows(data.table, page, search, next);
                }}
                title={data.maskedColumns.join(', ')}
              >
                {reveal ? <EyeOff size={14} /> : <Eye size={14} />}
                {reveal ? 'Hide' : 'Show'} {data.maskedColumns.length} credential
                {data.maskedColumns.length === 1 ? ' column' : ' columns'}
              </button>
            )}

            <span className="db-count">{number(data.total)} rows</span>

            {!openTable.protectedTable && data.total > 0 && (
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
              Credential columns are in clear. This was logged.
            </p>
          )}

          <div className="table-responsive">
            <table className="db-rows">
              <thead>
                <tr>
                  {data.columns.map((c) => (
                    <th key={c} className={data.maskedColumns.includes(c) ? 'masked' : ''}>{c}</th>
                  ))}
                  {data.primaryKey && <th className="db-actions-head"><span className="sr-only">Delete</span></th>}
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <tr><td colSpan={data.columns.length + 1} className="db-loading">
                    <Loader2 size={16} className="spin" /> Reading…
                  </td></tr>
                ) : data.rows.length === 0 ? (
                  <tr><td colSpan={data.columns.length + 1} className="db-loading">No rows.</td></tr>
                ) : data.rows.map((row) => (
                  <tr key={String(row[data.primaryKey] ?? JSON.stringify(row))}>
                    {data.columns.map((c) => <td key={c}>{cell(row[c])}</td>)}
                    {data.primaryKey && (
                      <td className="db-row-actions">
                        <button
                          className="icon-btn danger"
                          title="Delete this row"
                          onClick={() => setPending({
                            kind: 'row', table: data.table, key: String(row[data.primaryKey]),
                          })}
                        >
                          <Trash2 size={14} />
                        </button>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className="db-pager">
              <button
                disabled={page <= 1}
                onClick={() => { const p = page - 1; setPage(p); loadRows(data.table, p, search, reveal); }}
              >
                <ChevronLeft size={15} />
              </button>
              <span>Page {page} of {totalPages}</span>
              <button
                disabled={page >= totalPages}
                onClick={() => { const p = page + 1; setPage(p); loadRows(data.table, p, search, reveal); }}
              >
                <ChevronRight size={15} />
              </button>
            </div>
          )}
        </div>
      )}

      {/* Password stands between a live session and irreversible loss. */}
      {pending && (
        <div className="db-confirm-backdrop" onClick={() => !working && setPending(null)}>
          <div className="db-confirm" onClick={(e) => e.stopPropagation()}>
            <h3><AlertTriangle size={17} /> This cannot be undone</h3>

            {pending.kind === 'row' ? (
              <p>
                Deleting one row from <code>{pending.table}</code>. Anything that
                referenced it goes with it.
              </p>
            ) : (
              <>
                <p>
                  Emptying <code>{pending.table}</code> removes{' '}
                  <strong>{number(pending.rows)} rows</strong>. Type the table name to
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
