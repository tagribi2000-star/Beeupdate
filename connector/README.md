# Bee AI Connector

A free, mobile-friendly handoff page for ChatGPT, Claude, and GitHub.

## What it does
- Opens ChatGPT, Claude, and the selected GitHub repository.
- Builds reusable prompts and handoff messages.
- Keeps pasted text in the current browser page only; no API keys or backend.

## Important limits
- No automatic account linking or message transfer. Copy/paste is required.
- Each provider requires its own login and follows its free-tier limits.
- GitHub changes must be made through the user's own authorized GitHub workflow and reviewed before merging.

## Publish
Enable GitHub Pages for this repository with **GitHub Actions** as the source. The workflow in `.github/workflows/deploy-connector.yml` deploys the repository; the app will be at `/Beeupdate/connector/` on the account's GitHub Pages domain.
