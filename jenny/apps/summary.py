"""Agent-context summary of the installed Jenny Apps (progressive disclosure:
one line per app; AGENT.md stays lazy-read via the file tools)."""

from __future__ import annotations

from pathlib import Path

from jenny.apps.manifest import scan_apps


def build_apps_summary(workspace: Path) -> str:
    """One markdown bullet per app; empty string when there are no apps."""
    apps = scan_apps(workspace)
    if not apps:
        return ""
    lines: list[str] = []
    for app in apps:
        if app.broken or app.manifest is None:
            lines.append(
                f"- **{app.slug}** — BROKEN ({app.error}). "
                f"Fix `apps/{app.slug}/app.json` if the user asks."
            )
            continue
        # Composta a pezzi perche' le azioni sono opzionali: un'app di sola
        # visualizzazione ne ha zero, e un `— tools: ` vuoto in mezzo alla riga
        # sembrerebbe un elenco che non e' stato caricato.
        parts = [f"- **{app.manifest.name}** (`{app.slug}`) — {app.manifest.description}"]
        if app.manifest.actions:
            tools = ", ".join(f"`{app.slug}_{a.name}`" for a in app.manifest.actions)
            parts.append(f"tools: {tools}")
        else:
            parts.append("no agent-facing actions (display-only app)")
        parts.append(f"data: `apps/{app.slug}/data/`")
        if (app.dir / "AGENT.md").is_file():
            parts.append(f"context: `apps/{app.slug}/AGENT.md`")
        lines.append(" — ".join(parts))
    return "\n".join(lines)
