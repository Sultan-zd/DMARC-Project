# 🛡️ DMARC Web Dashboard

A comprehensive web dashboard for monitoring and analyzing **DMARC** (Domain-based Message Authentication, Reporting & Conformance) reports. Built to help SOC teams and IT administrators detect email spoofing, identify SPF/DKIM misconfigurations, and strengthen domain email security.

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white)
![License](https://img.shields.io/badge/License-MIT-blue)

---

## ✨ Features

- **📊 Dashboard** — Real-time KPIs (SPF/DKIM/DMARC pass rates), email volume charts, policy distribution, and top 10 sender analysis
- **📧 Automated IMAP Ingestion** — Fetches and parses DMARC XML reports from a configured mailbox (ZIP/GZ support)
- **📋 Report Browser** — Paginated, sortable, and filterable list of all ingested reports with detailed per-IP authentication records
- **🔍 Domain Analysis** — Real-time DNS audit of any domain's email security (DMARC, SPF, DKIM, MX, MTA-STS, BIMI) with a security score and actionable recommendations
- **🚨 Security Alerts** — Automated alert generation when failure rate thresholds are exceeded or volume spikes are detected
- **👥 Multi-User RBAC** — Role-based access control (Admin, Analyst, Viewer) with JWT authentication
- **📤 Data Export** — Export reports as CSV or PDF
- **🔐 Secure by Design** — BCrypt password hashing, stateless JWT, CORS protection

---

## 🏗️ Architecture

```text
┌─────────────────┐       ┌────────────────────┐       ┌──────────────────┐
│  React Frontend │◄─────►│  Spring Boot API   │◄─────►│    Database      │
│  (Vite, Router, │ JSON  │  (Port 8000, JWT)  │ JDBC  │  (H2 / Postgres) │
│   Recharts)     │       │                    │       │                  │
└─────────────────┘       └────────┬───────────┘       └──────────────────┘
                                   │ IMAP (993)
                                   ▼
                          ┌────────────────────┐
                          │   Mail Server      │
                          │  (DMARC Reports)   │
                          └────────────────────┘
```

---

## 🛠️ Tech Stack

| Layer | Technology |
|-------|-----------|
| **Backend** | Java 17, Spring Boot 3.3, Spring Data JPA, Spring Security |
| **Frontend** | React 19, Vite 8, React Router 7, Recharts 3, Lucide Icons |
| **Database** | H2 (development) / PostgreSQL (production) |
| **Authentication** | JWT (HMAC-SHA256) + BCrypt |
| **DNS Resolution** | dnsjava (live DNS lookups) |
| **Email** | Jakarta Mail (IMAP with SSL) |
| **API Docs** | Springdoc OpenAPI (Swagger UI) |

---

## 🚀 Quick Start

### Prerequisites

- **Java** JDK 17+
- **Maven** 3.8+ (or use the included `mvnw` wrapper)
- **Node.js** 18+
- **npm** 9+

### 1. Clone the Repository

```bash
git clone https://github.com/Sultan-zd/dmarc-web-dashboard.git
cd dmarc-web-dashboard
```

### 2. Start the Backend

```bash
cd backend
./mvnw spring-boot:run           # Linux/macOS
mvnw.cmd spring-boot:run         # Windows
```

The API server starts on `http://localhost:8000`.

### 3. Start the Frontend

```bash
cd frontend
npm install
npm run dev
```

The dashboard is accessible at `http://localhost:5173`.

### 4. Login

| Role | Username | Password |
|------|----------|----------|
| Admin | `admin` | `Admin@Dmarc2024!` |

> Additional users can be created from the Admin panel.

---

## ⚙️ Configuration

All backend settings are in `backend/src/main/resources/application.properties`:

```properties
# Server
server.port=8000

# Database (H2 - Development)
spring.datasource.url=jdbc:h2:file:./data/dmarc_dashboard
spring.datasource.username=sa
spring.datasource.password=

# JWT Authentication
app.jwt.secret=YOUR_SECRET_KEY_MIN_256_BITS
app.jwt.expiration-ms=3600000

# IMAP (DMARC Report Inbox)
app.imap.server=imap.example.com
app.imap.port=993
app.imap.username=dmarcreports@example.com
app.imap.password=your_password
app.imap.use-ssl=true
app.imap.polling-interval-minutes=15

# Alert Thresholds
app.alert.failure-rate-threshold=0.3
app.alert.spike-multiplier=2.0

# CORS
app.cors.origins=http://localhost:5173,http://localhost:3000
```

<details>
<summary><strong>🐘 PostgreSQL (Production)</strong></summary>

Replace the H2 database settings with:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/dmarc_db
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.username=postgres
spring.datasource.password=your_secure_password
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
```

</details>

---

## 📁 Project Structure

```
├── backend/                          # Spring Boot API (Java 17)
│   ├── src/main/java/com/teknologiia/dmarc/
│   │   ├── config/                   # CORS & Security configuration
│   │   ├── controller/               # REST endpoints
│   │   ├── dto/                      # Request/Response DTOs (Java records)
│   │   ├── model/                    # JPA entities
│   │   ├── repository/              # Spring Data JPA repositories
│   │   ├── security/                # JWT provider & auth filter
│   │   └── service/                 # Business logic (IMAP, parser, DNS)
│   ├── src/main/resources/
│   │   └── application.properties
│   └── pom.xml
│
├── frontend/                         # React SPA
│   ├── src/
│   │   ├── components/              # Reusable UI components
│   │   ├── context/                 # Auth context (JWT state)
│   │   ├── pages/                   # Page views (Dashboard, Reports, etc.)
│   │   ├── services/                # API client (fetch + JWT)
│   │   ├── App.jsx                  # Router configuration
│   │   └── index.css                # Global styles
│   ├── vite.config.js
│   └── package.json
│
├── GUIDE_COMPLET.md                  # Full technical documentation
└── README.md
```

---

## 🔌 API Endpoints

Interactive API docs available at: `http://localhost:8000/api/docs`

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/auth/login` | Authenticate and receive JWT token |
| `GET` | `/api/auth/me` | Get current user profile |
| `GET` | `/api/stats/overview` | Dashboard KPIs |
| `GET` | `/api/stats/timeline` | Email volume over time |
| `GET` | `/api/stats/top-senders` | Top sender IPs by volume |
| `GET` | `/api/reports` | List reports (paginated, filterable) |
| `GET` | `/api/reports/:id` | Report detail with auth records |
| `GET` | `/api/alerts` | List security alerts |
| `PATCH` | `/api/alerts/:id/read` | Mark alert as read |
| `POST` | `/api/analysis/domain` | Analyze domain DNS security |
| `GET` | `/api/analysis/history` | Past analysis results |
| `POST` | `/api/admin/ingest` | Trigger manual IMAP ingestion |
| `GET` | `/api/admin/users` | List all users |
| `POST` | `/api/admin/users` | Create new user |

---

## 📖 Documentation

For a complete technical guide covering architecture deep dives, scoring algorithms, security details, production deployment, and troubleshooting, see the **[Full Project Guide](GUIDE_COMPLET.md)**.

---

## 🚢 Production Deployment

1. **Migrate to PostgreSQL** — H2 is for development only
2. **Build the frontend**: `cd frontend && npm run build`
3. **Build the backend**: `cd backend && ./mvnw clean package -DskipTests`
4. **Run**: `java -jar target/dmarc-dashboard-*.jar`
5. **Serve** the `frontend/dist/` via Nginx with API proxy

See the [Full Guide](GUIDE_COMPLET.md#-11-production-deployment) for detailed Nginx, Docker, and security checklist.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

<p align="center">
  Built with ❤️ by <strong>Teknologiia</strong>
</p>
