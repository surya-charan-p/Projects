# Personal Finance & Transaction Platform

A full-stack financial transaction platform: Spring Boot REST API + React frontend + MySQL database.

## What's included

- **Authentication** — register/login with JWT, password hashing (BCrypt)
- **Account management** — open checking/savings/credit accounts, view balances, close accounts
- **Transfers** — move money between your own accounts or to anyone else's account number, with atomic balance updates (a transfer can never leave money "half moved")
- **Deposits & withdrawals** — add or remove funds directly on an account
- **Transaction history** — filterable (by type, status, amount, date) and paginated
- **Dashboard** — total balance, balance-by-account chart, recent activity feed
- **Admin panel** — view all users, enable/disable users, view/freeze/close any account (log in as the seeded admin, see below)

## Running it (Docker — recommended)

You need [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed. Then, from the project root:

```bash
docker compose up --build
```

This starts three containers: MySQL, the Spring Boot backend, and the React frontend (served via nginx).

Once it's up:
- **Frontend**: http://localhost:5173
- **Backend API**: http://localhost:8080/api

To stop everything: `docker compose down`. Add `-v` (`docker compose down -v`) if you also want to wipe the database volume and start completely fresh.

## Running it manually (no Docker)

**1. MySQL**
Install MySQL locally and create a database:
```sql
CREATE DATABASE finance_platform;
```
Update `backend/src/main/resources/application.yml` (or set environment variables `DB_USERNAME` / `DB_PASSWORD`) to match your local MySQL credentials.

**2. Backend**
Requires Java 17 and Maven.
```bash
cd backend
mvn spring-boot:run
```
Runs on http://localhost:8080.

**3. Frontend**
Requires Node.js 18+.
```bash
cd frontend
npm install
npm run dev
```
Runs on http://localhost:5173, and proxies `/api` calls to the backend automatically (see `vite.config.js`).

## Default admin login

On first startup, the backend automatically creates an admin account if one doesn't already exist:

- **Email**: `admin@financeapp.com`
- **Password**: `Admin@12345`

Log in with these to reach the Admin panel. **Change this password / remove the seeder in `DataSeeder.java` before using this anywhere near production.**

## Project structure

```
finance-platform/
├── backend/     Spring Boot API (Java 17, Spring Security + JWT, Spring Data JPA, MySQL)
├── frontend/    React app (Vite, React Router, Axios, Recharts)
└── docker-compose.yml
```

## Security notes for going further

This is a learning/demo-grade build. Before treating it as production-ready you'd want to at minimum:
- Move the JWT secret and DB credentials out of compose/config files and into a proper secrets manager
- Add refresh tokens (currently access tokens just expire after 24h with no refresh flow)
- Add rate limiting on `/api/auth/**` and transfer endpoints
- Add HTTPS/TLS termination in front of both services
- Remove or gate the `DataSeeder` admin bootstrap behind an environment flag
