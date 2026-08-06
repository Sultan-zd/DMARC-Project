import React from 'react';
import { X, KeyRound, Link2, Lock, Trash2, Copy } from 'lucide-react';
import { renderValue, relative, prettyJson } from './databaseFormat';

/**
 * One record, opened out.
 *
 * <p>A grid answers "how many" and "which ones"; it is the wrong shape for "what is
 * this row actually saying". Thirteen columns squeezed into a scrolling strip lose
 * every value that is longer than a word — a JSON breakdown, an error summary, a
 * recommendation — and none of the column names get to explain themselves.
 *
 * <p>So the same row is available vertically, where each field has room for its
 * meaning underneath it. This is the view that makes the console teachable rather
 * than merely usable.
 */
const RowDetail = ({ page, row, onClose, onDelete }) => {
  const key = page.primaryKey ? row[page.primaryKey] : null;

  const copy = (value) => navigator.clipboard?.writeText(String(value));

  return (
    <>
      <div className="db-detail-backdrop" onClick={onClose} />
      <aside className="db-detail" role="dialog" aria-label="Record detail">
        <header className="db-detail-head">
          <div>
            <span className="db-detail-eyebrow">{page.label}</span>
            <h3>
              {key !== null ? <>Record <span className="mono">#{key}</span></> : 'Record'}
            </h3>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Close">
            <X size={17} />
          </button>
        </header>

        <div className="db-detail-body">
          {page.columns.map((column) => {
            const value = row[column.name];
            const isJson = column.kind === 'json' && value;
            const ago = column.kind === 'date' ? relative(value) : null;

            return (
              <div className="db-field" key={column.name}>
                <div className="db-field-head">
                  <span className="db-field-label">{column.label}</span>

                  {column.primaryKey && (
                    <span className="db-field-tag" title="Primary key">
                      <KeyRound size={10} /> key
                    </span>
                  )}
                  {column.referencesTable && (
                    <span className="db-field-tag" title={`Points into ${column.referencesTable}`}>
                      <Link2 size={10} /> {column.referencesTable}
                    </span>
                  )}
                  {column.credential && (
                    <span className="db-field-tag secret" title="Hidden unless revealed">
                      <Lock size={10} /> secret
                    </span>
                  )}

                  <span className="db-field-type" title={column.nullable ? 'Nullable' : 'Required'}>
                    {column.sqlType}{column.nullable ? '' : ' · required'}
                  </span>
                </div>

                <div className="db-field-value">
                  {isJson ? (
                    <pre className="db-json">{prettyJson(value)}</pre>
                  ) : (
                    renderValue(value, column, page.references, false)
                  )}

                  {ago && <span className="db-field-ago">{ago} · stored in UTC</span>}

                  {value !== null && value !== undefined && !column.credential && (
                    <button
                      className="db-field-copy"
                      onClick={() => copy(value)}
                      title="Copy value"
                    >
                      <Copy size={12} />
                    </button>
                  )}
                </div>

                {/* The whole point of the panel: the column says what it is for. */}
                {column.description ? (
                  <p className="db-field-note">{column.description}</p>
                ) : (
                  <p className="db-field-note undocumented">
                    Not described yet — added to the schema after the dictionary was written.
                  </p>
                )}
              </div>
            );
          })}
        </div>

        {key !== null && (
          <footer className="db-detail-foot">
            <button className="btn-text-danger" onClick={() => onDelete(String(key))}>
              <Trash2 size={14} /> Delete this record
            </button>
          </footer>
        )}
      </aside>
    </>
  );
};

export default RowDetail;
