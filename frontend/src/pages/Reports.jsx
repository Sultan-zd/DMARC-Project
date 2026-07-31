import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import Header from '../components/layout/Header';
import StatusBadge from '../components/ui/StatusBadge';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import { useAuth } from '../context/AuthContext';
import * as api from '../services/api';
import { Search, Filter, Download, ChevronLeft, ChevronRight, Eye, RefreshCw, FileText } from 'lucide-react';
import './Reports.css';

const Reports = () => {
  const { token } = useAuth();
  const navigate = useNavigate();
  
  const [loading, setLoading] = useState(true);
  const [reports, setReports] = useState([]);
  const [total, setTotal] = useState(0);
  
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [domain, setDomain] = useState('');
  const [policy, setPolicy] = useState('');
  const [sortBy, setSortBy] = useState('date_begin');
  const [sortOrder, setSortOrder] = useState('desc');
  
  const loadReports = async () => {
    setLoading(true);
    try {
      const data = await api.getReports(token, {
        page,
        page_size: pageSize,
        domain,
        policy,
        sort_by: sortBy,
        sort_order: sortOrder
      });
      setReports(data.items || []);
      setTotal(data.total || 0);
    } catch (error) {
      console.error("Error loading reports:", error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (token) {
      loadReports();
    }
  }, [token, page, pageSize, sortBy, sortOrder]);

  const handleSort = (field) => {
    if (sortBy === field) {
      setSortOrder(sortOrder === 'asc' ? 'desc' : 'asc');
    } else {
      setSortBy(field);
      setSortOrder('desc');
    }
  };

  const applyFilters = () => {
    setPage(1);
    loadReports();
  };

  const resetFilters = () => {
    setDomain('');
    setPolicy('');
    setPage(1);
    // Let useEffect trigger load when state settles, or trigger explicitly if needed
  };

  const handleExportCSV = () => {
    api.exportCSV(token, { domain, policy });
  };

  const handleExportPDF = () => {
    api.exportPDF(token, { domain, policy });
  };

  const totalPages = Math.ceil(total / pageSize) || 1;

  const formatDate = (dateString) => {
    if (!dateString) return '';
    const d = new Date(dateString);
    return new Intl.DateTimeFormat('en-US', { 
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit'
    }).format(d);
  };

  const formatNumber = (num) => new Intl.NumberFormat('en-US').format(num || 0);

  return (
    <div className="reports-page">
      <Header title="DMARC Reports" subtitle="View and analyze detailed reports" />
      
      <div className="filter-bar">
        <div className="filter-group">
          <Search className="filter-icon" size={18} />
          <input 
            type="text" 
            className="filter-input" 
            placeholder="Search by domain..." 
            value={domain}
            onChange={(e) => setDomain(e.target.value)}
          />
        </div>
        <div className="filter-group">
          <Filter className="filter-icon" size={18} />
          <select 
            className="filter-select"
            value={policy}
            onChange={(e) => setPolicy(e.target.value)}
          >
            <option value="">All policies</option>
            <option value="none">None</option>
            <option value="quarantine">Quarantine</option>
            <option value="reject">Reject</option>
          </select>
        </div>
        <button className="btn-primary" onClick={applyFilters}>Filter</button>
        <button className="btn-secondary" onClick={resetFilters}>Reset</button>
      </div>

      <div className="action-bar">
        <div className="total-display">
          <span>{formatNumber(total)} reports found</span>
        </div>
        <div className="export-actions">
          <button className="btn-secondary btn-icon" onClick={handleExportCSV}>
            <Download size={16} /> CSV
          </button>
          <button className="btn-secondary btn-icon" onClick={handleExportPDF}>
            <Download size={16} /> PDF
          </button>
          <button className="search-btn" onClick={() => loadReports()}>
            <RefreshCw size={20} className={loading ? 'spin' : ''} />
          </button>
        </div>
      </div>

      {!loading && reports.length === 0 && (
        <div className="glass-card" style={{ textAlign: 'center', padding: '4rem 2rem', marginTop: '2rem' }}>
          <div style={{ display: 'inline-flex', padding: '1rem', background: 'var(--bg-secondary)', borderRadius: '50%', marginBottom: '1.5rem' }}>
            <FileText size={48} color="var(--text-muted)" />
          </div>
          <h2 style={{ marginBottom: '1rem' }}>No DMARC Reports Received Yet</h2>
          <p style={{ color: 'var(--text-muted)', maxWidth: '600px', margin: '0 auto 2rem', lineHeight: '1.6' }}>
            This page lists the historical DMARC XML reports sent by ISPs. 
            To see data here, ensure your domain's DMARC record contains an <code>rua</code> email address, and ingest the received XML files in the Admin panel. 
            The Domain Analysis tool does not populate this page.
          </p>
        </div>
      )}

      {reports.length > 0 && (
        <div className="reports-table-container glass-card fade-in">
          {loading ? (
            <div className="loading-wrapper"><LoadingSpinner /></div>
          ) : (
            <table className="reports-table">
              <thead>
                <tr>
                  <th onClick={() => handleSort('org_name')}>Organization {sortBy === 'org_name' && (sortOrder === 'asc' ? '↑' : '↓')}</th>
                  <th onClick={() => handleSort('domain')}>Domain {sortBy === 'domain' && (sortOrder === 'asc' ? '↑' : '↓')}</th>
                  <th onClick={() => handleSort('date_begin')}>Date {sortBy === 'date_begin' && (sortOrder === 'asc' ? '↑' : '↓')}</th>
                  <th onClick={() => handleSort('policy')}>Policy {sortBy === 'policy' && (sortOrder === 'asc' ? '↑' : '↓')}</th>
                  <th onClick={() => handleSort('record_count')}>Records {sortBy === 'record_count' && (sortOrder === 'asc' ? '↑' : '↓')}</th>
                  <th onClick={() => handleSort('total_emails')}>Emails {sortBy === 'total_emails' && (sortOrder === 'asc' ? '↑' : '↓')}</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {reports.map((report) => (
                  <tr key={report.id} onClick={() => navigate(`/reports/${report.id}`)}>
                    <td>{report.org_name}</td>
                    <td className="font-mono">{report.domain}</td>
                    <td>{formatDate(report.date_begin)}</td>
                    <td>
                      <StatusBadge status={report.policy} />
                    </td>
                    <td>{formatNumber(report.record_count)}</td>
                    <td>{formatNumber(report.total_emails)}</td>
                    <td>
                      <button className="btn-icon-only" onClick={(e) => {
                        e.stopPropagation();
                        navigate(`/reports/${report.id}`);
                      }}>
                        <Eye size={18} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}

      <div className="pagination">
        <button 
          className="btn-icon" 
          disabled={page === 1} 
          onClick={() => setPage(page - 1)}
        >
          <ChevronLeft size={20} />
        </button>
        <span className="page-info">Page {page} of {totalPages}</span>
        <button 
          className="btn-icon" 
          disabled={page >= totalPages} 
          onClick={() => setPage(page + 1)}
        >
          <ChevronRight size={20} />
        </button>
        <select 
          className="page-size-select"
          value={pageSize}
          onChange={(e) => {
            setPageSize(Number(e.target.value));
            setPage(1);
          }}
        >
          <option value="10">10 per page</option>
          <option value="20">20 per page</option>
          <option value="50">50 per page</option>
          <option value="100">100 per page</option>
        </select>
      </div>
    </div>
  );
};

export default Reports;
