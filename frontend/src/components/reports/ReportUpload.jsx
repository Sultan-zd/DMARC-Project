import React, { useCallback, useRef, useState } from 'react';
import { UploadCloud, FileArchive, FileText, X, CheckCircle2, AlertTriangle } from 'lucide-react';
import * as api from '../../services/api';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import './ReportUpload.css';

/**
 * A hint for the file picker, not a rule.
 *
 * The browser uses this to decide which files to highlight; it does not stop
 * anyone choosing another, and nothing here rejects one. The server identifies
 * a report from its first bytes, so a file this list does not mention is still
 * worth sending — refusing it in the browser would only hide a report the
 * server could have read.
 */
const ACCEPTED = '.xml,.gz,.gzip,.zip,application/zip,application/gzip';

const formatSize = (bytes) => {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
};

/**
 * Drop zone for DMARC aggregate reports.
 *
 * Providers ship reports as .xml, .xml.gz or .zip, so all three are accepted and
 * unpacked server-side. Calls `onUploaded` once anything was actually stored.
 */
const ReportUpload = ({ onUploaded }) => {
  const { token } = useAuth();
  const { showToast } = useToast();
  const inputRef = useRef(null);

  const [files, setFiles] = useState([]);
  const [isDragging, setIsDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState(null);

  const addFiles = useCallback((incoming) => {
    // Nothing is filtered here any more, and that is deliberate.
    //
    // This used to drop anything whose name did not end .xml, .gz or .zip, which
    // rejected the file before it was ever sent — so a report saved out of a
    // mailbox under a name the provider chose, or a gateway rewrote, looked to the
    // person uploading it like the application refusing their data. The server
    // reads the first bytes and knows what a file is regardless of its name, and it
    // says clearly when something is not a report. Deciding that here, on a
    // filename, could only be wrong in the direction that loses reports.
    const candidates = Array.from(incoming);
    if (candidates.length === 0) return;

    setResult(null);
    setFiles((current) => {
      // Re-dropping the same file should not queue it twice.
      const seen = new Set(current.map((file) => `${file.name}:${file.size}`));
      return [...current, ...candidates.filter((file) => !seen.has(`${file.name}:${file.size}`))];
    });
  }, []);

  const handleDrop = (event) => {
    event.preventDefault();
    setIsDragging(false);
    addFiles(event.dataTransfer.files);
  };

  const handleDragOver = (event) => {
    event.preventDefault();
    setIsDragging(true);
  };

  const removeFile = (index) => {
    setFiles((current) => current.filter((_, i) => i !== index));
  };

  const openPicker = () => inputRef.current?.click();

  const handleUpload = async () => {
    if (files.length === 0) return;

    setUploading(true);
    setResult(null);
    try {
      const response = await api.uploadReports(token, files);
      setResult(response);
      setFiles([]);

      if (response.reportsStored > 0) {
        showToast(
          `${response.reportsStored} report(s) imported — ${response.recordsStored} records`,
          'success',
        );
        onUploaded?.(response);
      } else if (response.duplicatesSkipped > 0) {
        showToast('Already imported — nothing new to add', 'info');
      } else {
        showToast('No report could be imported', 'error');
      }
    } catch (error) {
      console.error('Upload failed:', error);
      showToast('Upload failed', 'error');
      setResult({ reportsStored: 0, recordsStored: 0, duplicatesSkipped: 0, errors: [error.message] });
    } finally {
      setUploading(false);
    }
  };

  return (
    <div className="report-upload">
      <div
        className={`upload-dropzone ${isDragging ? 'is-dragging' : ''}`}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
        onDragLeave={() => setIsDragging(false)}
        onClick={openPicker}
        onKeyDown={(event) => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            openPicker();
          }
        }}
        role="button"
        tabIndex={0}
        aria-label="Add DMARC report files"
      >
        <UploadCloud size={34} className="upload-icon" />
        <p className="upload-title">Drop DMARC reports here</p>
        <p className="upload-hint">or click to browse — .xml, .xml.gz and .zip archives</p>

        <input
          ref={inputRef}
          type="file"
          multiple
          accept={ACCEPTED}
          className="upload-input"
          onChange={(event) => {
            addFiles(event.target.files);
            // Reset so picking the same file again still fires a change event.
            event.target.value = '';
          }}
        />
      </div>

      {files.length > 0 && (
        <ul className="upload-queue">
          {files.map((file, index) => (
            <li key={`${file.name}-${file.size}-${index}`} className="upload-queue-item">
              {/\.zip$/i.test(file.name) ? <FileArchive size={16} /> : <FileText size={16} />}
              <span className="upload-file-name">{file.name}</span>
              <span className="upload-file-size">{formatSize(file.size)}</span>
              <button
                type="button"
                className="upload-remove"
                onClick={() => removeFile(index)}
                aria-label={`Remove ${file.name}`}
                disabled={uploading}
              >
                <X size={14} />
              </button>
            </li>
          ))}
        </ul>
      )}

      {files.length > 0 && (
        <button
          type="button"
          className="btn btn-primary w-full"
          onClick={handleUpload}
          disabled={uploading}
        >
          {uploading
            ? 'Importing…'
            : `Import ${files.length} file${files.length > 1 ? 's' : ''}`}
        </button>
      )}

      {result && (
        <div className={`upload-result ${result.reportsStored > 0 ? 'success' : 'warning'}`}>
          <div className="upload-result-head">
            {result.reportsStored > 0
              ? <CheckCircle2 size={16} />
              : <AlertTriangle size={16} />}
            <span>
              {result.reportsStored} imported
              {result.duplicatesSkipped > 0 && ` · ${result.duplicatesSkipped} already known`}
              {result.recordsStored > 0 && ` · ${result.recordsStored} records`}
            </span>
          </div>

          {result.errors?.length > 0 && (
            <ul className="upload-errors">
              {result.errors.map((error, index) => (
                <li key={index}>{error}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
};

export default ReportUpload;
