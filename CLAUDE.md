# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DeltaV is a patient registration form for healthcare onboarding, built with vanilla HTML, CSS, and JavaScript. Zero external dependencies — no package manager, no build system, no framework.

## Development

**Running locally:** Serve files with any HTTP server (e.g., `python -m http.server` or VS Code Live Server). No build step required.

**No test framework is configured.**

## Architecture

The entire app is three files:

- **index.html** — Semantic HTML5 form with ARIA accessibility attributes
- **form.js** — All logic wrapped in a single IIFE (`"use strict"`) to avoid global scope pollution
- **styles.css** — CSS variables for theming at `:root`, responsive breakpoint at 640px

### Form Logic (form.js)

- **Conditional rendering:** Radio button for "registrant" type toggles visibility of relationship-to-patient fields via the `hidden` attribute
- **Validation:** Client-side, runs on submit. Email regex, future-date prevention for DOB, conditional required fields based on registrant selection
- **Redirect security:** `ALLOWED_HOSTS` whitelist validates the `next`/`redirect` query parameter before navigating. Defaults include `localhost`, `127.0.0.1`, `leadsmanagementweb.revenuewell.com`, and the current host
- **Error display:** Errors shown inline with ARIA alert roles; `clearErrors()` and `showError()` helpers manage DOM state

### CSS Conventions

- CSS custom properties for colors, radius, shadow, font
- BEM-like class naming (`.section-title`, `.field-label`, `.checkbox-group`)
- Mobile-first responsive layout with `@media (min-width: 640px)`

### Form Sections

1. Registrant type (self vs. another person) — conditionally shows relationship fields
2. Patient info (name, DOB)
3. Contact info (phone, email)
4. Consent checkboxes (accuracy confirmation, HIPAA/privacy acknowledgment)