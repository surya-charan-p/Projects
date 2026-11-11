# Projects

A collection of things I've built, from small frontend apps to full-stack
systems. Each folder (or linked repo) has its own README with setup steps.

## Featured

### ⛽ [Fuel Tracker](https://github.com/surya-charan-p/fuel-tracker) · own repo

Mobile-first, installable web app (PWA) for logging fill-ups and tracking a
vehicle's real fuel economy, cost per km and fuel spend.

- **Stack:** React 19, TypeScript, TanStack Query, Tailwind · Python 3.13, FastAPI, SQLAlchemy 2, PostgreSQL 17 · Docker, GitHub Actions
- **Highlights:** JWT auth with an httpOnly refresh cookie; full-tank economy method with exact decimal maths; frontend types generated from the API's OpenAPI schema; pytest, Vitest and Playwright tests (phone + desktop) in CI
- **Docs:** architecture, API, design decisions and a roadmap through live fuel prices and AI predictions

[![CI](https://github.com/surya-charan-p/fuel-tracker/actions/workflows/ci.yml/badge.svg)](https://github.com/surya-charan-p/fuel-tracker/actions/workflows/ci.yml)

## In this repo

### 💳 [Finance Platform](finance-platform/)

Full-stack personal finance and transaction platform: accounts, transfers,
deposits and withdrawals, filterable transaction history, a dashboard and an
admin panel.

- **Stack:** Java 17, Spring Boot, Spring Security + JWT, Spring Data JPA, MySQL · React, Vite, React Router, Recharts · Docker Compose, nginx
- **Highlights:** atomic transfers (money is never "half moved"), BCrypt password hashing, role-based admin features, paginated and filtered queries
- **Run:** `cd finance-platform && docker compose up --build` → http://localhost:5173

### 🧠 [Quiz App](quiz-app/)

Browser trivia game: answer questions against a countdown timer and climb a
prize ladder, with sound effects for right and wrong answers.

- **Stack:** React 18 (Create React App), CSS, `use-sound`
- **Run:** `cd quiz-app && npm install && npm start`

## Tech at a glance

| | Fuel Tracker | Finance Platform | Quiz App |
|---|:-:|:-:|:-:|
| Frontend | React 19 + TypeScript | React + Vite | React |
| Backend | FastAPI (Python) | Spring Boot (Java) | — |
| Database | PostgreSQL | MySQL | — |
| Auth | JWT + refresh cookie | JWT | — |
| Containers | Docker Compose | Docker Compose | — |
| CI / tests | GitHub Actions, pytest, Vitest, Playwright | — | — |
