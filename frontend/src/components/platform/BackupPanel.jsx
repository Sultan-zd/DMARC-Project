import React, { useCallback, useEffect, useState } from 'react';
import {
  HardDriveDownload, Loader2, Download, AlertTriangle, CheckCircle2, Archive,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { formatStamp, relative, formatBytes } from './databaseFormat';
import * as api from '../../services/api';
import './BackupPanel.css';

/**
 * Whether there is a second copy of the data, and how old it is.
 *
 * <p>The age is the number that earns this panel its place. Backups rarely fail
 * loudly — they stop, and nothing says so until the day one is needed. Putting the
 * age on a page an operator already opens, and turning it red when it grows, is
 * what keeps them working; the schedule itself is the easy part.
 */
const BackupPanel = () => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [status, setStatus] = useState(null);
  const [running, setRunning] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await api.getBackupStatus(token));
    } catch (err) {
      showToast(err.message, 'error');
    }
  }, [token, showToast]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const runNow = async () => {
    setRunning(true);
    try {
      const { detail } = await api.runBackup(token);
      showToast(detail, 'success');
      await load();
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setRunning(false);
    }
  };

  const download = async (name) => {
    try {
      // Fetched rather than linked: the endpoint needs the bearer token, which an
      // anchor cannot carry.
      const blob = await api.downloadBackup(token, name);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      link.click();
      URL.revokeObjectURL(url);
      showToast('Downloaded. Keep it somewhere other than this machine.', 'success');
    } catch (err) {
      showToast(err.message, 'error');
    }
  };

  if (!status) return null;

  const blocked = !status.configured || !status.toolAvailable;

  return (
    <section className="platform-card backup-panel">
      <header className="backup-head">
        <div>
          <h2><HardDriveDownload size={17} /> Backups</h2>
          <p className="backup-lead">
            The database volume is one disk fault, or one <code>down -v</code>, away
            from being no copies at all. These are the second copy — and they live on
            the same machine, so getting one off it is the point of the download.
          </p>
        </div>
        {status.configured && status.toolAvailable && (
          <button className="btn btn-secondary" onClick={runNow} disabled={running}>
            {running
              ? <><Loader2 size={15} className="spin" /> Running…</>
              : <><Archive size={15} /> Back up now</>}
          </button>
        )}
      </header>

      {/* The one line worth reading first. */}
      {!status.configured ? (
        <div className="backup-banner bad">
          <AlertTriangle size={17} />
          <div>
            <strong>No backups are being taken.</strong>
            <span>
              Nothing is configured, so the database volume is the only copy of every
              report, analysis and account. Set <code>BACKUP_DIR</code> — the
              container image already has somewhere to put them.
            </span>
          </div>
        </div>
      ) : !status.toolAvailable ? (
        <div className="backup-banner bad">
          <AlertTriangle size={17} />
          <div>
            <strong><code>mariadb-dump</code> is not on this image.</strong>
            <span>
              A directory is configured but nothing can be written to it. This is
              normal outside the container; inside it, the image needs rebuilding.
            </span>
          </div>
        </div>
      ) : status.count === 0 ? (
        <div className="backup-banner warn">
          <AlertTriangle size={17} />
          <div>
            <strong>Configured, but nothing has been written yet.</strong>
            <span>
              The first scheduled run happens shortly after start. Press
              <em> Back up now</em> rather than waiting to find out.
            </span>
          </div>
        </div>
      ) : status.healthy ? (
        <div className="backup-banner good">
          <CheckCircle2 size={17} />
          <div>
            <strong>Last backup {relative(status.newestAt)}.</strong>
            <span>
              {status.count} kept, {formatBytes(Math.round(status.totalBytes / 1024))} in
              total, one every {status.intervalHours} hours.
            </span>
          </div>
        </div>
      ) : (
        <div className="backup-banner bad">
          <AlertTriangle size={17} />
          <div>
            <strong>
              The newest backup is {status.ageHours} hours old.
            </strong>
            <span>
              The schedule is every {status.intervalHours} hours, so it has missed at
              least two runs. Something has stopped — this is the state that is
              usually discovered on the day a backup is needed.
            </span>
          </div>
        </div>
      )}

      {status.lastError && (
        <p className="backup-error">
          <AlertTriangle size={13} /> Last attempt failed: {status.lastError}
        </p>
      )}

      {!blocked && status.recent.length > 0 && (
        <>
          <ul className="backup-list">
            {status.recent.map((entry) => (
              <li key={entry.name}>
                <div className="backup-when">
                  <span className="backup-stamp">{formatStamp(entry.at)}</span>
                  <span className="backup-ago">{relative(entry.at)}</span>
                </div>
                <span className="backup-name">{entry.name}</span>
                <span className="backup-size">
                  {formatBytes(Math.round(entry.bytes / 1024))}
                </span>
                <button
                  className="btn-tiny"
                  onClick={() => download(entry.name)}
                  title="Download this backup"
                >
                  <Download size={13} /> Download
                </button>
              </li>
            ))}
          </ul>

          <p className="backup-note">
            Keeping the newest {status.keepCount}; older ones are removed after each
            run. They sit on a separate volume from the database — but on the same
            host, so a copy that never leaves this machine is not yet a backup. And
            one that has never been restored is not one either: try it against a
            throwaway database before you need to.
          </p>
        </>
      )}
    </section>
  );
};

export default BackupPanel;
