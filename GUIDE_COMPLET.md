# 📘 DMARC Web Dashboard — Complete Project Guide

Welcome to the official documentation for the **DMARC Web Dashboard** project. This guide covers every aspect of the application — from the concepts behind email authentication to detailed installation, configuration, API reference, and production deployment instructions.

---

## 📖 1. Introduction

### What is DMARC?
**DMARC** (Domain-based Message Authentication, Reporting, and Conformance) is an email authentication protocol designed to protect domains against spoofing and phishing attacks.
DMARC builds on two existing mechanisms: **SPF** and **DKIM**. It enables domain owners to publish a policy instructing receiving mail servers what to do when an email fails authentication checks (reject it, quarantine it, or take no action). Additionally, DMARC generates detailed XML reports about email activity sent from the domain.

### What is SPF?
**SPF** (Sender Policy Framework) is a DNS record (TXT type) that lists the IP addresses and servers authorized to send emails on behalf of a domain.
* **Common mechanisms:**
  * `include:` — includes third-party service servers (e.g., `include:_spf.google.com`).
  * `ip4:` / `ip6:` — authorizes a specific IP address or subnet.
  * `mx` — authorizes the domain's MX servers.
  * `all` — catch-all mechanism, usually prefixed with `-` (hard fail / reject), `~` (soft fail), or `?` (neutral).

### What is DKIM?
**DKIM** (DomainKeys Identified Mail) adds a cryptographic signature to outgoing emails. The sender signs the email with a private key, and the recipient verifies the signature using a public key published in the domain's DNS under a specific **selector** (e.g., `selector1._domainkey.example.com`).
* **Alignment:** For DMARC to pass, the domain in the "From" address must match (align with) the domain verified by either SPF or DKIM.

### Why This Dashboard? (The Problem It Solves)
Mail servers generate DMARC reports as raw XML files, often compressed inside ZIP or GZ archives. For a SOC (Security Operations Center) team or an IT administrator, manually analyzing hundreds of XML reports is impractical and error-prone.

This **DMARC Web Dashboard** automates the retrieval of these reports via IMAP, extracts attachments, parses the XML, and stores the structured data in a relational database. It then presents the data through a modern web interface, enabling users to quickly identify spoofing attacks, SPF/DKIM misconfigurations, and safely improve their DMARC policy over time.

### Email Flow Diagram
```text
[ Sender ] ---> [ MTA (Mail Server) ] ---> [ Receiver (Gmail, Outlook) ]
                                                    |
                                                    |-- 1. Verify SPF
                                                    |-- 2. Verify DKIM
                                                    |-- 3. Evaluate DMARC
                                                    |
                                          [ XML Report Generated ]
                                                    |
                                                    v
[ DMARC Web Dashboard ] <--- (IMAP) --- [ Inbox (rua=mailto:...) ]
        |
        |-- XML Parsing
        |-- Database (H2 / PostgreSQL)
        |-- REST API (Spring Boot)
        |-- Web Interface (React)
```

---

## 🏗️ 2. Project Architecture

### Architecture Overview
```text
+---------------------+       +-----------------------+       +------------------------+
|                     |       |                       |       |                        |
|  Frontend (React)   | <---> | REST API (Spring Boot)| <---> | Database               |
|  (Vite, React       | JSON  | (Port 8000, JWT Auth) | JDBC  | (H2 dev / PostgreSQL)  |
|   Router, Recharts) |       |                       |       |                        |
+---------------------+       +-----------+-----------+       +------------------------+
                                          |
                                          | IMAP (Port 993, SSL)
                                          v
                              +-----------------------+
                              | Mail Server (Inbox)   |
                              | dmarcreports@...      |
                              +-----------------------+
```

### Full Directory Structure
```text
DMARC Web Dashboard Project/
├── backend/                          # Spring Boot Server (Java 17)
│   ├── src/main/java/com/teknologiia/dmarc/
│   │   ├── DmarcDashboardApplication.java   # Entry point
│   │   ├── config/                   # CORS, Security Configuration
│   │   ├── controller/               # REST API Endpoints
│   │   │   ├── AuthController.java         # POST /api/auth/login
│   │   │   ├── StatsController.java        # GET /api/stats/*
│   │   │   ├── ReportController.java       # GET /api/reports
│   │   │   ├── AlertController.java        # GET /api/alerts
│   │   │   ├── DomainAnalysisController.java  # POST /api/analysis/domain
│   │   │   ├── AdminController.java        # Admin endpoints
│   │   │   └── HealthController.java       # Health check
│   │   ├── dto/                      # Java Records (Request/Response payloads)
│   │   ├── model/                    # JPA Entities (User, DmarcReport, DmarcRecord, Alert)
│   │   ├── repository/              # Spring Data JPA interfaces
│   │   ├── security/                # JWT token provider, authentication filter
│   │   └── service/                 # Business logic
│   │       ├── EmailService.java          # IMAP connection & email fetching
│   │       ├── DmarcParserService.java    # XML parsing (DOM-based)
│   │       ├── ReportService.java         # Report retrieval & pagination
│   │       ├── DomainAnalysisService.java # Live DNS lookups (dnsjava)
│   │       └── AlertService.java          # Alert generation & management
│   ├── src/main/resources/
│   │   └── application.properties    # All backend configuration
│   └── pom.xml                       # Maven dependencies
│
├── frontend/                         # React SPA
│   ├── src/
│   │   ├── components/
│   │   │   ├── layout/              # Sidebar, Header
│   │   │   └── ui/                  # StatCard, StatusBadge, ScoreGauge,
│   │   │                            #   DnsRecordCard, LoadingSpinner, etc.
│   │   ├── context/                 # AuthContext (JWT state management)
│   │   ├── pages/                   # Full-page views
│   │   │   ├── Login.jsx            # Authentication page
│   │   │   ├── Dashboard.jsx        # KPIs, charts, top senders
│   │   │   ├── Reports.jsx          # Paginated report list with filters
│   │   │   ├── ReportDetail.jsx     # Individual report with IP records
│   │   │   ├── Alerts.jsx           # Security alerts (filterable)
│   │   │   ├── DomainAnalysis.jsx   # Real-time DNS audit tool
│   │   │   └── Admin.jsx            # User management & manual ingestion
│   │   ├── services/
│   │   │   └── api.js               # Centralized API client (fetch + JWT)
│   │   ├── App.jsx                  # React Router configuration
│   │   ├── main.jsx                 # Application entry point
│   │   └── index.css                # Global styles & CSS variables
│   ├── index.html                   # HTML shell
│   ├── vite.config.js               # Vite dev server configuration
│   └── package.json                 # npm dependencies
│
├── GUIDE_COMPLET.md                  # This documentation file
└── README.md                        # Quick-start readme
```

### Data Flow
1. **Ingestion:** The `EmailService` connects to the IMAP mailbox, downloads unread emails, and extracts `.zip` / `.gz` attachments containing DMARC XML reports.
2. **Parsing:** The `DmarcParserService` reads each XML file using a DOM parser, extracts report metadata, source IPs, SPF/DKIM results, and creates `DmarcReport` and `DmarcRecord` entities.
3. **Storage:** Entities are persisted to the relational database via Spring Data JPA repositories. Alerts are automatically generated when failure rate thresholds are exceeded.
4. **API:** REST controllers expose the data in paginated, structured format using DTOs (Data Transfer Objects).
5. **Frontend:** The React application consumes the JWT-secured API and renders interactive charts, tables, gauges, and alert feeds.

### Technology Stack

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Backend Runtime | Java 17, Spring Boot 3.3 | REST API, business logic, security |
| Database (Dev) | H2 (file-based) | Lightweight, zero-config database |
| Database (Prod) | PostgreSQL | Production-grade relational database |
| DNS Resolution | dnsjava | Live DNS TXT/MX record lookups |
| Email Retrieval | Jakarta Mail (IMAP) | Fetch DMARC report emails from mailbox |
| Frontend | React 19, Vite 8 | Modern reactive UI with hot reload |
| Routing | React Router 7 | Client-side page navigation |
| Charts | Recharts 3 | Area charts, pie charts, tooltips |
| Icons | Lucide React | Consistent SVG icon library |
| Authentication | JWT (HMAC-SHA256) | Stateless token-based authentication |
| API Documentation | Springdoc (Swagger) | Interactive API testing interface |

### Design Patterns
* **MVC (Model-View-Controller):** The backend separates data models (JPA entities), business logic (services), and HTTP endpoints (controllers).
* **Repository Pattern:** Spring Data JPA abstracts database access through interface-based repositories.
* **DTO (Data Transfer Object):** Java `record` types transmit only the necessary data fields without exposing internal database entities.
* **Stateless JWT Authentication:** No server-side sessions are stored. Each request carries a signed JWT token that is validated cryptographically.
* **Context API (React):** The `AuthContext` provides user authentication state to the entire component tree without prop drilling.

---

## 🛠️ 3. Prerequisites & Installation

### System Requirements
* **Java:** JDK 17 or later
* **Maven:** 3.8+ (or use the included wrapper `./mvnw` / `mvnw.cmd`)
* **Node.js:** Version 18 or later
* **npm:** Version 9 or later

### Step-by-Step Installation

1. **Navigate to the project folder**
   Open a terminal in the project directory:
   ```bash
   cd "DMARC Web Dashboard Project"
   ```

2. **Build the Backend (Spring Boot)**
   ```bash
   cd backend
   ./mvnw clean install -DskipTests     # Linux/macOS
   mvnw.cmd clean install -DskipTests   # Windows
   ```
   This downloads all Maven dependencies and compiles the application into a `.jar` file.

3. **Install Frontend Dependencies (React)**
   ```bash
   cd ../frontend
   npm install
   ```
   This installs all npm packages: React, React Router, Recharts, Lucide React, and Vite.

---

## ⚙️ 4. Configuration

All backend configuration is managed in `backend/src/main/resources/application.properties`.

### Configuration File Breakdown
```properties
# Server port
server.port=8000

# ----- Database (H2 - Development) -----
spring.datasource.url=jdbc:h2:file:./data/dmarc_dashboard
spring.datasource.driver-class-name=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update    # Auto-creates/updates schema
spring.jpa.show-sql=false
spring.h2.console.enabled=true          # Enables web-based DB explorer
spring.h2.console.path=/h2-console

# ----- JWT Security -----
# Secret key (MUST be changed in production - minimum 256 bits / 32 characters)
app.jwt.secret=bXktc3VwZXItc2VjcmV0LWtleS0...
app.jwt.expiration-ms=3600000           # Token lifetime: 1 hour

# ----- IMAP Server (Report Fetching) -----
app.imap.server=imap.teknologiia.com
app.imap.port=993
app.imap.username=dmarcreports@teknologiia.com
app.imap.password=                      # Set your IMAP password here
app.imap.use-ssl=true
app.imap.polling-interval-minutes=15    # Email check frequency

# ----- Default Admin Account -----
app.admin.username=admin
app.admin.password=Admin@Dmarc2024!

# ----- Alert Thresholds -----
app.alert.failure-rate-threshold=0.3    # Alert if >30% failure rate
app.alert.spike-multiplier=2.0          # Alert if volume suddenly doubles

# ----- CORS (Cross-Origin Resource Sharing) -----
app.cors.origins=http://localhost:5173,http://localhost:3000

# ----- Swagger / API Documentation -----
springdoc.api-docs.path=/api/api-docs
springdoc.swagger-ui.path=/api/docs
```

### Switching to PostgreSQL (Production)
To migrate from H2 to PostgreSQL, replace the database properties:
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/dmarc_db
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.username=postgres
spring.datasource.password=your_secure_password
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```
Spring's Hibernate will automatically create the required tables on first startup.

---

## 🚀 5. Quick Start

### Start the Backend
Open a terminal in the `backend/` directory:
```bash
./mvnw spring-boot:run           # Linux/macOS
mvnw.cmd spring-boot:run         # Windows
```
The server will start on `http://localhost:8000`.

### Start the Frontend
Open a **separate** terminal in the `frontend/` directory:
```bash
npm run dev
```
The application will be accessible at `http://localhost:5173`.

### Default Credentials
* **Username:** `admin`
* **Password:** `Admin@Dmarc2024!`

Additional users can be created from the Admin panel.

### Useful URLs

| URL | Description |
|-----|-------------|
| `http://localhost:5173` | Frontend Dashboard |
| `http://localhost:8000/api/docs` | Swagger API Documentation (interactive) |
| `http://localhost:8000/h2-console` | H2 Database Console (JDBC URL: `jdbc:h2:file:./data/dmarc_dashboard`) |

---

## 👥 6. User Guide

### Login Page
Access to the dashboard is restricted. Enter your credentials to log in. Passwords are hashed using BCrypt and are never stored in plain text. Upon successful authentication, a JWT token is stored in `localStorage` and automatically attached to all subsequent API requests.

### Dashboard
The main page provides a high-level overview of your email security posture.

* **KPI Cards:** Total reports, total emails processed, SPF pass rate, DKIM pass rate, DMARC pass rate, and number of unique source IPs.
* **Email Volume Chart:** An area chart showing the trend of SPF pass vs. fail over the last 30 days.
* **Policy Distribution:** A donut chart breaking down reports by DMARC policy (`none`, `quarantine`, `reject`).
* **Top 10 Senders:** A table listing the IP addresses sending the most emails for your domains, with individual SPF and DKIM pass rates displayed as progress bars.
* **What to do if rates are low?** If the DMARC pass rate is below 90%, check the "Top Senders" table to identify legitimate servers that are failing (they need SPF/DKIM configuration) versus unauthorized senders (potential spoofing).

### Reports
The **Reports** page displays all ingested DMARC XML reports in a paginated table.

* **Filters:** Search by domain name and filter by policy type (`none`, `quarantine`, `reject`).
* **Sorting:** Click any column header (Organization, Domain, Date, Policy, Records, Emails) to sort ascending or descending.
* **Export:** Download the filtered data as CSV or PDF.
* **Detail View:** Click any report row (or the eye icon) to navigate to the Report Detail page, which shows the full metadata and a table of every authentication record — each source IP with its SPF result, DKIM result, disposition, and associated domains.

### Alerts
The **Alerts** page lists security events detected by the system.

* **Severity Levels:** `CRITICAL` (e.g., sudden spoofing spike detected), `HIGH` (e.g., failure rate exceeds 30%), `MEDIUM`, `LOW`.
* **Statistics Bar:** Shows total alerts, unread count, and breakdown by critical/high severity.
* **Filters:** Filter by severity level and read/unread status.
* **Actions:** Mark individual alerts as read, or use "Mark all as read" to clear notifications. A critical alert warrants immediate investigation of SPF/DKIM logs for the affected domain.

### Domain Analysis
The **Domain Analysis** tool performs real-time DNS audits of any domain's email security configuration.

1. Enter a domain name (e.g., `google.com`).
2. The backend performs live DNS lookups against public DNS servers (Google 8.8.8.8).
3. **Security Score & Grade:** You receive a score from 0 to 100 and a letter grade (A+ to F).
4. **DNS Record Cards:** Each email security record is individually analyzed and displayed — DMARC, SPF, DKIM, MX, and BIMI — with the raw DNS value, a status indicator, and detailed findings.
5. **Recommendations:** Actionable suggestions are generated with severity levels. For example, if your DMARC policy is `none`, the tool recommends upgrading to `quarantine` or `reject`.
6. **Educational Section:** A built-in "How This Analysis Works" section explains the DNS lookup process, what each check does, and how the scoring is calculated.
7. **History:** All past analyses are saved and can be reviewed or compared over time.
8. **Export:** Click "Export PDF" to print the analysis results for sharing or archival.

### Admin Panel
Accessible only to users with the `admin` role.

* **User Management:** View all registered users with their roles and status. Create new user accounts with one of three roles: Administrator, Analyst, or Viewer.
* **Manual Ingestion:** Trigger an immediate IMAP connection to fetch and parse DMARC report emails without waiting for the automatic 15-minute polling cycle. The result shows how many emails were processed and how many reports were stored.

---

## 🔌 7. API Reference

The REST API is fully documented and testable via the Swagger UI at `/api/docs`.
All endpoints (except authentication) require the header: `Authorization: Bearer <token>`.

### Authentication

**POST `/api/auth/login`**
```bash
curl -X POST http://localhost:8000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@Dmarc2024!"}'
```
Response:
```json
{ "access_token": "eyJ...", "role": "admin" }
```

**GET `/api/auth/me`**
Returns the currently authenticated user's profile.

---

### Statistics

**GET `/api/stats/overview`**
Query params: `domain`, `date_from`, `date_to`
```json
{
  "total_reports": 150,
  "total_emails": 45000,
  "spf_pass_rate": 94.2,
  "dkim_pass_rate": 91.8,
  "dmarc_pass_rate": 95.5,
  "unique_sources": 42,
  "policy_distribution": { "none": 30, "quarantine": 50, "reject": 70 }
}
```

**GET `/api/stats/timeline`**
Query params: `days` (default 30)
Returns daily aggregated data for charting (date, spf_pass, spf_fail counts).

**GET `/api/stats/top-senders`**
Query params: `limit` (default 10)
Returns top IP senders with email counts and pass rates.

**GET `/api/stats/domains`**
Returns per-domain statistics breakdown.

---

### Reports

**GET `/api/reports`**
Query params: `page` (default 1), `page_size` (default 20), `domain`, `org_name`, `source_ip`, `date_from`, `date_to`, `policy`, `sort_by` (default `date_begin`), `sort_order` (default `desc`)
```json
{
  "items": [...],
  "total": 100,
  "page": 1,
  "page_size": 20,
  "total_pages": 5
}
```

**GET `/api/reports/{id}`**
Returns full report details including all authentication records (source IPs, SPF/DKIM results, dispositions, domains).

---

### Alerts

**GET `/api/alerts`**
Query params: `severity`, `is_read`
Returns paginated list of security alerts.

**GET `/api/alerts/count`**
Returns alert statistics: `{ total, unread, critical, high }`.

**PATCH `/api/alerts/{id}/read`**
Marks a single alert as read.

**PATCH `/api/alerts/mark-all-read`**
Marks all alerts as read.

---

### Domain Analysis

**POST `/api/analysis/domain`**
```bash
curl -X POST http://localhost:8000/api/analysis/domain \
  -H "Authorization: Bearer TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"domain":"example.com"}'
```
Response:
```json
{
  "domain": "example.com",
  "score": { "score": 85, "grade": "B", "color": "blue" },
  "records": [...],
  "recommendations": [
    { "severity": "warning", "message": "...", "action": "..." }
  ]
}
```

**GET `/api/analysis/history`**
Returns paginated list of past domain analyses.

**GET `/api/analysis/{id}`**
Returns the full details of a specific past analysis.

---

### Admin

**GET `/api/admin/users`** — List all users.
**POST `/api/admin/users`** — Create a new user.
```json
{ "username": "analyst1", "email": "analyst@company.com", "password": "...", "role": "analyst" }
```
**POST `/api/admin/ingest`** — Manually trigger IMAP email ingestion.

---

### Export

**GET `/api/export/csv`** — Download filtered reports as a CSV file.
**GET `/api/export/pdf`** — Download filtered reports as a PDF file.
Query params: `domain`, `policy`

---

## 🧩 8. Code Architecture Deep Dive

### Backend (Spring Boot — Java 17)

The backend is organized by layer within the `com.teknologiia.dmarc` package:

| Package | Purpose | Key Files |
|---------|---------|-----------|
| `config/` | CORS configuration, Spring Security setup. Disables CSRF (stateless API), declares public routes (`/api/auth/**`). | `SecurityConfig.java` |
| `model/` | JPA entities annotated with `@Entity`. Defines database schema and relationships (`@OneToMany` between `DmarcReport` and `DmarcRecord`). | `DmarcReport.java`, `DmarcRecord.java`, `User.java`, `Alert.java` |
| `repository/` | Interfaces extending `JpaRepository`. Custom `@Query` annotations for SQL aggregations. | `DmarcReportRepository.java` |
| `dto/` | Java `record` types for immutable, concise request/response payloads. | `PaginatedResponse.java`, `ReportListResponse.java`, `ReportDetailResponse.java` |
| `service/` | Core business logic. | See below |
| `controller/` | HTTP entry points. Inject services, return `ResponseEntity`. | `ReportController.java`, `StatsController.java` |
| `security/` | JWT token generation/validation (using JJWT library) and request interception filter. | `JwtTokenProvider.java`, `JwtAuthenticationFilter.java` |

**Key Services:**
* **`EmailService`** — Connects to the IMAP mailbox using Jakarta Mail, downloads unread emails with DMARC report attachments, and passes them to the parser.
* **`DmarcParserService`** — Uses Java's DOM `DocumentBuilder` to parse DMARC XML files. Handles namespaces, extracts report metadata, IP records, SPF/DKIM results.
* **`ReportService`** — Retrieves reports from the database with filtering and pagination support.
* **`DomainAnalysisService`** — Performs live DNS queries using the `dnsjava` library against Google's public DNS (8.8.8.8). Checks DMARC, SPF, DKIM (multiple selectors), MX, MTA-STS, TLSRPT, and BIMI records. Calculates a security score and generates recommendations.
* **`AlertService`** — Monitors ingested reports for anomalies (high failure rates, volume spikes) and generates alerts with severity levels.

### Frontend (React 19 + Vite 8)

| File/Directory | Purpose |
|---------------|---------|
| `src/services/api.js` | Centralized API client built on `fetch`. Automatically injects the JWT token from `localStorage`, builds query strings, and handles error responses (including 401 auto-logout). |
| `src/context/AuthContext.jsx` | React Context providing authentication state (`token`, `user`, `loading`, `login`, `logout`) to the entire application. |
| `src/App.jsx` | Defines all routes using React Router. Implements `ProtectedRoute` (requires authentication) and `AdminRoute` (requires admin role) wrapper components. |
| `src/components/layout/` | `Sidebar.jsx` (navigation with active state), `Header.jsx` (page title/subtitle). |
| `src/components/ui/` | Reusable UI components: `StatCard`, `StatusBadge`, `ScoreGauge` (SVG circular gauge), `DnsRecordCard` (expandable DNS record display), `AnimatedCounter`, `LoadingSpinner`, `SkeletonLoader`. |
| `src/pages/` | Full-page view components. Each page manages its own data fetching, state, and layout. |

---

## 🔎 9. Domain Analysis — Technical Details

The analysis engine performs **real-time live DNS lookups** (not cached data) to audit a domain's email security configuration.

### DNS Records Checked

1. **DMARC** (`_dmarc.domain.com` TXT) — Checks for `v=DMARC1`, policy `p=` (none, quarantine, reject), and percentage `pct=`.
2. **SPF** (`domain.com` TXT) — Looks for `v=spf1`. Analyzes `include`, `ip4`, `ip6` mechanisms. Verifies the qualifier (`-all` recommended). Counts DNS lookups induced (strict limit of 10 per RFC 7208).
3. **DKIM** (`selector._domainkey.domain.com` TXT) — Tests multiple common selectors (`google`, `default`, `s1`, `s2`, `selector1`, `selector2`). Verifies `v=DKIM1; k=rsa; p=...` and detects key length (1024-bit = weak, 2048-bit = strong).
4. **MX** (`domain.com` MX) — Verifies that mail exchange servers are configured.
5. **MTA-STS** (`_mta-sts.domain.com` TXT) — Checks if strict TLS encryption is enforced for incoming mail.
6. **TLSRPT** (`_smtp._tls.domain.com` TXT) — Checks TLS reporting configuration.
7. **BIMI** (`default._bimi.domain.com` TXT) — Checks for brand logo display in compatible inboxes (requires a VMC certificate).

### Scoring System

Points are awarded based on the strength and completeness of the email security configuration:

| Check | Points | Condition |
|-------|--------|-----------|
| DMARC present | +20 | `v=DMARC1` found |
| DMARC `p=reject` | +15 | Strongest enforcement policy |
| DMARC `p=quarantine` | +10 | Moderate enforcement |
| SPF present | +20 | `v=spf1` found |
| SPF `-all` (hard fail) | +10 | Strict enforcement |
| DKIM found | +20 | Valid DKIM key discovered |
| MX configured | +5 | Mail exchange servers exist |
| MTA-STS / BIMI | +5 each | Advanced configurations |

**Penalties:** Exceeded SPF DNS lookup limit (>10), weak DKIM key (<2048 bits), invalid syntax.

### Grade Scale

| Score Range | Grade | Meaning |
|------------|-------|---------|
| 90 – 100 | **A+** | Excellent — Full enforcement with strong cryptography |
| 80 – 89 | **A** | Very Good — Strong protection |
| 60 – 79 | **B** | Good — Monitoring mode, improvements possible |
| 40 – 59 | **C** | Fair — Missing major protocols |
| < 40 | **F** | Critical — Highly vulnerable to spoofing |

---

## 🔒 10. Security

### JWT Authentication Flow
1. The client sends `username` and `password` via `POST /api/auth/login`.
2. Spring Security verifies the password hash (BCrypt) against the database.
3. If valid, the server generates a JWT token signed with HMAC-SHA256 using the secret key from `app.jwt.secret`. The token payload contains the username, role, and expiration timestamp.
4. The client stores the token in `localStorage` and includes it in every request via `Authorization: Bearer <token>`.
5. The `JwtAuthenticationFilter` intercepts each request, extracts the token, validates the cryptographic signature, and sets the security context — **without querying the database** (stateless).

### Roles & Permissions

| Role | Capabilities |
|------|-------------|
| **Admin** | Full access: view all data, create users, trigger manual ingestion |
| **Analyst** | View data, export reports, run domain analyses |
| **Viewer** | Read-only access to dashboards and reports |

### Password Hashing
Passwords are **never** stored in plain text. The `BCryptPasswordEncoder` generates a random salt and hashes the password (typically 10 rounds). Even if the database is compromised, passwords cannot be reversed.

### CORS Protection
To prevent unauthorized cross-origin requests, Spring Boot restricts API access to the origins specified in `app.cors.origins`. Only the official frontend (e.g., `http://localhost:5173`) is allowed to call the API from a browser.

---

## 🏭 11. Production Deployment

### Step 1: Migrate from H2 to PostgreSQL
H2 is not suitable for production workloads.
1. Install PostgreSQL on your server.
2. Create a database: `CREATE DATABASE dmarc_db;`
3. Update `application.properties` with PostgreSQL credentials (see Section 4).
4. On first startup, Hibernate will automatically create all required tables.

### Step 2: Build the Frontend
```bash
cd frontend
npm run build
```
This generates a `dist/` directory containing optimized, minified HTML/JS/CSS files ready for static hosting.

### Step 3: Build the Backend
```bash
cd backend
./mvnw clean package -DskipTests
```
The resulting JAR file (`target/dmarc-dashboard-*.jar`) can be run with:
```bash
java -jar target/dmarc-dashboard-0.0.1-SNAPSHOT.jar
```

### Step 4: Nginx Reverse Proxy (Example)
Serve the frontend and proxy API requests through Nginx:
```nginx
server {
    listen 80;
    server_name dashboard.yourcompany.com;

    # Frontend (React build)
    location / {
        root /var/www/dmarc-frontend/dist;
        try_files $uri $uri/ /index.html;
    }

    # API Proxy
    location /api/ {
        proxy_pass http://localhost:8000/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

### Step 5: Docker (Recommended)
Use `docker-compose` to run PostgreSQL, the backend (Java 17 container), and the frontend (Nginx container) as a unified stack.

### Production Checklist
- [ ] Change `app.jwt.secret` to a strong, unique 256-bit key
- [ ] Set a secure IMAP password in `app.imap.password`
- [ ] Migrate to PostgreSQL
- [ ] Configure HTTPS (TLS certificate via Let's Encrypt)
- [ ] Update `app.cors.origins` to your production domain
- [ ] Set `spring.jpa.hibernate.ddl-auto=validate` (prevent accidental schema changes)
- [ ] Disable H2 console (`spring.h2.console.enabled=false`)

---

## 🚑 12. Troubleshooting

| Problem | Solution |
|---------|----------|
| **Backend won't start (Port in use)** | Another process is using port 8000. Kill it with `netstat -ano | findstr :8000` (Windows) or `lsof -i :8000` (Linux), then terminate the PID. Alternatively, change `server.port` in `application.properties`. |
| **CORS error in browser console** | The frontend URL is not listed in `app.cors.origins`. Ensure it matches exactly (no trailing `/`). |
| **JWT invalid / Sudden logout** | The token expired (1-hour lifetime). Log in again. If it happens immediately, check that server and client clocks are synchronized. |
| **IMAP connection fails** | Verify credentials in `application.properties`. For Gmail or Office 365, you need an **App Password**, not your regular account password. Ensure port 993 (IMAPS) is not blocked by your firewall. |
| **DNS analysis timeout** | Your server's firewall is blocking outbound UDP port 53. Allow DNS queries to external resolvers. |
| **H2 database locked** | You're running two backend instances pointing to the same `./data/dmarc_dashboard` file. Stop one instance. |
| **Reports page shows blank** | Check the browser console for JavaScript errors. This was previously caused by missing icon imports (`RefreshCw`, `FileText`) — this has been fixed. |
| **Frontend build fails** | Run `npm install` again to ensure all dependencies are installed. Check Node.js version (18+ required). |

---

## 📚 13. Glossary

| Term | Definition |
|------|-----------|
| **MTA** (Mail Transfer Agent) | Server responsible for routing and delivering emails. |
| **Spoofing** | Forging the sender address ("From" field) to impersonate someone else. |
| **Phishing** | Social engineering attack via email, using a spoofed identity to trick the recipient. |
| **Softfail (~)** | SPF mechanism that accepts the email but marks it as suspicious. |
| **Hardfail (-)** | SPF mechanism that instructs the receiver to reject the email. |
| **Alignment** | DMARC concept verifying that the domain used for signing (DKIM) or verification (SPF) matches the domain visible to the end user in the "From" header. |
| **RUA** (Reporting URI for Aggregate) | DMARC tag specifying the email address (e.g., `mailto:dmarcreports@...`) where daily XML aggregate reports should be sent. |
| **BIMI** (Brand Indicators for Message Identification) | Standard allowing organizations to display a certified logo next to their messages in supported inboxes. |
| **VMC** (Verified Mark Certificate) | Digital certificate required for BIMI logo display, issued by a certificate authority. |
| **BCrypt** | Password hashing algorithm used by Spring Security. Incorporates a random salt and is computationally expensive to brute-force. |
| **JWT** (JSON Web Token) | Compact, URL-safe token format used for stateless authentication between client and server. |

---

*DMARC Web Dashboard — Built with Spring Boot & React by Teknologiia*
