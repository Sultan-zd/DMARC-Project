# 🛡️ DMARC Web Dashboard

Email security monitoring for organizations: analyse a domain's DNS configuration,
collect the aggregate reports mailbox providers send back, and see who is sending
mail as your domains.

Built for [Teknologiia](https://www.teknologiia.com).

![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=black)
![Vite](https://img.shields.io/badge/Vite-8-646CFF?logo=vite&logoColor=white)
![Docker](https://img.shields.io/badge/Docker-compose-2496ED?logo=docker&logoColor=white)
![Tests](https://img.shields.io/badge/tests-211%20passing-success)
![License](https://img.shields.io/badge/License-MIT-blue)

> 📘 **[Guide complet (français)](COMPLETE_GUIDE.md)** — architecture, fonctionnement,
> configuration et mise en ligne, expliqués de A à Z.

---

## What it does

**Analyse any domain, live.** Reads DMARC, SPF, DKIM, MX and BIMI straight from
public DNS and grades the result out of 100. Nothing is sent to the domain and no
mailbox access is needed, so it works on domains you do not own. Available without
an account, from the landing page.

**Collect the reports.** Mailbox providers send daily XML reports to whatever
address a DMARC record names in `rua=`. Each organization points the dashboard at
its own mailbox and reports are collected automatically, or uploaded by hand.

**See what was actually sent.** The dashboard aggregates those reports: volume,
authentication pass rates, which sources send as your domains, and which of them
fail.

Two things it deliberately keeps apart, because they answer different questions. The
**analysis** says whether a domain *can* be spoofed. The **dashboard** says what was
*actually sent*. A domain can score perfectly and still show failures — that means
the configuration is sound but a legitimate sender is missing from it.

## Screens

| | |
|---|---|
| **Landing** | Public. Explains DMARC and offers a free domain check. |
| **Dashboard** | Traffic from the collected reports, plus each domain's configuration posture. |
| **Reports** | Every stored report, filterable, exportable as CSV or branded PDF. |
| **Alerts** | Volume spikes, failure rates, and findings raised by analyses. |
| **Analysis** | Run a check, see the grade with its breakdown, and what to fix first. |
| **Admin** | Per organization: invitations, claimed domains, accounts, report intake. |
| **Settings** | Profile, password, two-step verification, what your role may do, deployment facts. |
| **Platform** | For whoever runs the service: every organization in counts and health, plus a database console where each table and column carries what it is for. |

## Multi-tenancy

Every account belongs to an **organization**, and every read is scoped to it.
Reports, analyses, alerts, invitations and mailbox credentials are separated —
colleagues share a workspace, nobody else sees it.

Two ways to join one, both deliberate:

- **Invitation** — an emailed single-use link, valid seven days, revocable before
  it is used.
- **Verified email domain** — publish a TXT record proving you own `example.com`,
  and anyone signing up with an address there joins automatically instead of
  creating a second organization carrying the same company name.

## Getting started

### With Docker

Nothing to install but Docker itself — no Java, no Node, no database.

```bash
git clone https://github.com/Sultan-zd/DMARC-Project.git
cd DMARC-Project
cp .env.example .env        # then read it; every value has a working default
docker compose up --build
```

The dashboard is on **http://localhost:8000**, interface and API on one port. The
first start creates an administrator and prints its generated password **once**:

```bash
docker compose logs app | grep -A3 "Generated password"
```

| | |
|---|---|
| `docker compose up -d` | Start in the background |
| `docker compose logs -f app` | Follow the application log |
| `docker compose down` | Stop, keeping the data |
| `docker compose down -v` | Stop and **destroy the database** |

The database volume is the only copy of your data and nothing backs it up. Before
`down -v`, and periodically after that:

```bash
docker compose exec db mariadb-dump -u root -p"$DB_ROOT_PASSWORD" dmarc_dashboard > backup.sql
```

### Without Docker

**Requirements** — Java 17+, Node 20+, MariaDB or MySQL (a stock XAMPP works).

```bash
# 1. Clone and create the database
git clone https://github.com/Sultan-zd/DMARC-Project.git
cd DMARC-Project
mysql -u root -e "CREATE DATABASE dmarc_dashboard CHARACTER SET utf8mb4"

# 2. Backend — http://localhost:8000
cd backend
./mvnw spring-boot:run

# 3. Frontend — http://localhost:5173
cd frontend
npm install
npm run dev
```

The first start creates an administrator account and prints its generated password
**once**, in the log. Sign in with it and change it immediately.

Local secrets belong in `backend/config/application.properties`, which is
gitignored. Copy [`backend/config.example.properties`](backend/config.example.properties)
as a starting point.

## Going online

Both routes below collapse the two development ports into one origin, so `/api`
stops being a cross-origin call and a single address serves everything.

**With Docker**, that is already the case — point a tunnel or a reverse proxy at
port 8000 and set the address links are built from:

```bash
PUBLIC_URL=https://dmarc.example.com   # in .env
APP_TRUST_PROXY_HEADERS=true           # only behind a proxy you control
docker compose up -d --build
```

**Without Docker**, `go-online.ps1` builds the interface into the backend, generates
a persistent signing key, and sets the same address:

```powershell
.\go-online.ps1 -PublicUrl https://your-address.example.com
```

## Configuration

Everything is an environment variable, with defaults suited to local work.

| Variable | Purpose |
|---|---|
| `DB_URL` · `DB_USERNAME` · `DB_PASSWORD` | Database connection |
| `DB_DDL_AUTO` | `update` while building, `validate` in production |
| `JWT_SECRET` | Token signing key. Unset ⇒ generated at startup, so sessions die on restart |
| `SECRETS_KEY` | Encrypts stored mailbox passwords. Unset ⇒ they cannot be stored at all |
| `PUBLIC_URL` | The address emailed links point at |
| `MAIL_HOST` · `MAIL_PORT` · `MAIL_USERNAME` · `MAIL_PASSWORD` · `MAIL_FROM` | Outgoing mail |
| `PLATFORM_OPERATORS` | Usernames that also operate the service |
| `APP_CORS_ORIGINS` | Extra allowed origins; the application's own is always allowed |
| `APP_TRUST_PROXY_HEADERS` | `true` behind a proxy you control |
| `ADMIN_USERNAME` · `ADMIN_PASSWORD` · `ADMIN_EMAIL` | The first account, created only on an empty database |

## Security

- Passwords are BCrypt-hashed. Mailbox passwords are AES-GCM **encrypted** rather
  than hashed, because IMAP needs them back — and the application refuses to store
  one at all when no key is configured.
- **Two-step verification**: TOTP (RFC 6238), ten single-use recovery codes, and a
  challenge token that cannot itself open a session.
- **Roles enforced at the API**, not merely hidden in the interface: Administrator,
  Analyst, Viewer.
- **Hardened parsing** — XXE disabled, decompression bounded, archive entries capped.
- **Rate limiting** on sign-in, sign-up and the public scanner.

## Tests

```bash
cd backend && ./mvnw test
```

211 tests, run against an in-memory database that the build forces on every one of
them — a suite able to reach a real database is a suite able to destroy it.

The ones worth knowing about: tenant isolation across reports, analyses and
mailboxes; the published scoring model held to what the engine actually awards; TOTP
checked against RFC 6238's own test vectors; SQL-injection attempts through the
database console's table names; the console's column descriptions held to the live
schema, so a renamed column fails the build rather than losing its explanation; and
every client-side route asserted to survive a reload.

## Licence

[MIT](LICENSE)
