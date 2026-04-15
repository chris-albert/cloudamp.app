# Conductor Process Management

## IMPORTANT: Do NOT start long-running processes

Do NOT use the Bash tool to start dev servers, watchers, or any long-running process.
They will be automatically killed when the tool completes.

Instead, when the user asks you to start a server or long-running process:
1. Tell the user the exact command to run (e.g. `pnpm dev`, `npm run dev`)
2. Explain that they should run it via the **Processes panel** in the Conductor UI
3. You can verify it's running afterward by checking if the port is in use

Short-lived commands (install, build, git, file operations, tests) are fine to run directly.
