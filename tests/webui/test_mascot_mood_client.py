"""L'umore della mascotte lato client, eseguito davvero, e il contratto con il backend.

Il frame ``mascot_mood`` arriva **dopo** il ``turn_end``, cioè quando la mascotte è
tornata ``idle`` e niente di ciò che le guardie di ``_handleWsMessage`` guardano è
ancora "a schermo": va trattato prima di quelle guardie, e in entrambe le viste.
Da lì le regole sono poche e si misurano qui: una faccia si mostra solo a
mascotte intera e ferma, si scarta se è la reazione a un turno che non è più
l'ultimo o se un altro turno è in corso, scade da sola, e un turno nuovo la
azzera. Il tutto senza toccare il parlato, che ha il suo animatore.

I metodi si estraggono dal sorgente e girano in node su un ``this`` finto con
una ``classList`` minima: non toccano il DOM oltre a quella. Il contratto in
coda tiene allineate le etichette Python (``MOODS``) e la mappa JS
(``MOOD_ART``), e pretende che ogni posa presa in prestito esista davvero e sia
nel manifest Android — l'arte dedicata non c'è ancora e qui non la si prevede.
"""

from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path

import pytest

from jenny.session.mascot_mood import MOODS, NEUTRAL_MOOD
from jenny.utils.android_assets import _UI_MANIFEST

ASSETS = Path(__file__).resolve().parents[2] / "jenny" / "templates" / "ui" / "assets"
JENNY_JS = ASSETS / "mobile-jenny.js"

_NODE = shutil.which("node")


def _method(source: str, name: str) -> str:
    # ``(.*?)`` e non ``([^)]*)``: i parametri possono avere parentesi dentro
    # (``now = performance.now()``), e la firma finisce alla prima ``) {``.
    body = re.search(rf"\n  (?:async )?{name}\((.*?)\)\s*\{{(.*?)\n  \}}", source, re.S)
    assert body, f"{name} non trovato"
    return f"{name}({body.group(1)}) {{{body.group(2)}\n  }}"


def _const(source: str, name: str) -> str:
    # Il ``;`` puo' essere seguito da un commento di riga: senza ammetterlo la
    # cattura correrebbe fino al ``;`` della costante successiva.
    m = re.search(rf"^const {name} = (.*?);[ \t]*(?://.*)?$", source, re.S | re.M)
    assert m, f"{name} non trovata"
    return m.group(1)


def _mood_art(source: str) -> dict[str, str]:
    block = _const(source, "MOOD_ART")
    return dict(re.findall(r"(\w+):\s*'([^']+)'", block))


def _harness(*, standby: bool = False) -> str:
    # Lo standby lo si spegne nell'harness: qui si misura il meccanismo. Un test
    # a parte lo accende e pretende che non cambi niente.
    jenny = JENNY_JS.read_text(encoding="utf-8")
    methods = "\n".join(
        _method(jenny, name) + ","
        for name in (
            "_onMoodFrame",
            "_acceptMood",
            "_noteTurnClosed",
            "_applyMood",
            "_clearMood",
            "_moodPose",
            "_armWorry",
            "_disarmWorry",
        )
    )
    return f"""
import assert from 'node:assert/strict';

const MOOD_STANDBY = {"true" if standby else "false"};
const MOOD_ART = {_const(jenny, "MOOD_ART")};
const MOOD_HOLD_MS = {_const(jenny, "MOOD_HOLD_MS")};
const MOOD_WORRY_AFTER_MS = {_const(jenny, "MOOD_WORRY_AFTER_MS")};

function classList(...names) {{
  const set = new Set(names);
  return {{
    contains: (n) => set.has(n),
    add: (n) => set.add(n),
    remove: (n) => set.delete(n),
  }};
}}

function makeMascot(...classes) {{
  const m = {{
    el: {{ classList: classList(...classes) }},
    _mood: null, _moodUntil: 0, _moodTimer: null, _worryTimer: null,
    _lastClosedTurnId: null, _streamTurnId: null,
    _turnActive: false, _pendingTurn: false, _agentState: 'idle',
    _reducedMotion: false,
    syncs: 0,
    _syncArt() {{ this.syncs++; }},
    {methods}
  }};
  return m;
}}
"""


def _run_js(script: str, *, standby: bool = False) -> None:
    source = _harness(standby=standby) + script
    proc = subprocess.run(
        [str(_NODE), "--input-type=module", "-e", source],
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert proc.returncode == 0, proc.stderr or proc.stdout


node = pytest.mark.skipif(_NODE is None, reason="node non disponibile")


@node
def test_a_mood_frame_after_the_closed_turn_is_shown_when_out_and_idle() -> None:
    _run_js("""
      const m = makeMascot('out');
      m._noteTurnClosed({ event: 'turn_end', turn_id: 'webui:A' });
      m._onMoodFrame({ event: 'mascot_mood', mood: 'happy', turn_id: 'webui:A' });
      assert.equal(m._mood, 'happy');
      assert.equal(m._moodPose(), 'happy');
      assert.ok(m.syncs >= 1, 'la faccia non è stata ridisegnata');
      m._clearMood();
    """)


@node
def test_a_frame_without_turn_id_is_accepted_and_neutral_or_unknown_is_not() -> None:
    _run_js("""
      const m = makeMascot('out');
      m._noteTurnClosed({ event: 'turn_end', turn_id: 'webui:A' });
      assert.equal(m._acceptMood({ mood: 'sad' }), true, 'senza id vale per il turno corrente');
      assert.equal(m._acceptMood({ mood: 'neutral' }), false);
      assert.equal(m._acceptMood({ mood: 'ecstatic' }), false);
      assert.equal(m._acceptMood({ mood: 'toString' }), false, 'niente prototype walking');
      assert.equal(m._acceptMood(null), false);
    """)


@node
def test_a_reaction_to_a_turn_that_is_no_longer_the_latest_is_dropped() -> None:
    _run_js("""
      const m = makeMascot('out');
      m._noteTurnClosed({ event: 'turn_end', turn_id: 'webui:A' });
      m._noteTurnClosed({ event: 'turn_end', turn_id: 'webui:B' });
      assert.equal(m._acceptMood({ mood: 'happy', turn_id: 'webui:A' }), false);
      assert.equal(m._acceptMood({ mood: 'happy', turn_id: 'webui:B' }), true);
    """)


@node
def test_a_closing_frame_without_id_remembers_the_followed_turn() -> None:
    """Il retry di una consegna parziale arriva senza annotazione: vale il turno seguito."""
    _run_js("""
      const m = makeMascot('out');
      m._streamTurnId = 'webui:C';
      m._noteTurnClosed({ event: 'turn_end' });
      assert.equal(m._lastClosedTurnId, 'webui:C');
    """)


@node
def test_a_frame_while_another_turn_is_in_flight_is_dropped() -> None:
    _run_js("""
      const m = makeMascot('out');
      m._turnActive = true;
      assert.equal(m._acceptMood({ mood: 'happy' }), false);
      m._turnActive = false; m._pendingTurn = true;
      assert.equal(m._acceptMood({ mood: 'happy' }), false);
    """)


@node
def test_the_face_shows_only_when_out_still_and_not_flying() -> None:
    _run_js("""
      const docked = makeMascot();
      docked._applyMood('happy');
      assert.equal(docked._moodPose(), null, 'da side una faccia a metà non si legge');
      docked.el.classList.add('out');
      assert.equal(docked._moodPose(), 'happy', 'richiamata entro il tempo, la faccia riappare');
      docked.el.classList.add('flying');
      assert.equal(docked._moodPose(), null, 'in volo comanda il volo');
      docked._clearMood();
    """)


@node
def test_during_a_think_only_worried_shows() -> None:
    _run_js("""
      const m = makeMascot('out', 'thinking');
      m._applyMood('happy');
      assert.equal(m._moodPose(), null);
      m._applyMood('worried');
      assert.equal(m._moodPose(), 'worried');
      m._clearMood();
    """)


@node
def test_the_face_expires_on_its_own() -> None:
    _run_js("""
      const m = makeMascot('out');
      const t0 = 1000;
      m._applyMood('sad', t0);
      assert.equal(m._moodPose(t0 + MOOD_HOLD_MS - 1), 'sad');
      assert.equal(m._moodPose(t0 + MOOD_HOLD_MS), null);
      m._clearMood();
    """)


@node
def test_clearing_redraws_once_and_is_idempotent() -> None:
    _run_js("""
      const m = makeMascot('out');
      m._applyMood('sad');
      const before = m.syncs;
      m._clearMood();
      assert.equal(m._mood, null);
      assert.equal(m.syncs, before + 1);
      m._clearMood();
      assert.equal(m.syncs, before + 1, 'senza umore non c\\'è niente da ridisegnare');
    """)


@node
def test_the_worry_timer_arms_once_and_disarms_cleanly() -> None:
    _run_js("""
      const m = makeMascot('out', 'thinking');
      m._agentState = 'thinking';
      m._armWorry();
      const first = m._worryTimer;
      assert.ok(first, 'il timer non è partito');
      m._armWorry();
      assert.equal(m._worryTimer, first, 'riarmare non deve creare un secondo timer');
      m._disarmWorry();
      assert.equal(m._worryTimer, null);
      m._disarmWorry();
    """)


@node
def test_standby_changes_nothing_from_any_source() -> None:
    """Con MOOD_STANDBY acceso né il frame né il livello 0 toccano la posa."""
    _run_js("""
      const m = makeMascot('out');
      m._noteTurnClosed({ event: 'turn_end', turn_id: 'webui:A' });
      m._onMoodFrame({ event: 'mascot_mood', mood: 'happy', turn_id: 'webui:A' });
      m._applyMood('sad');
      assert.equal(m._mood, null);
      assert.equal(m._moodPose(), null);
      assert.equal(m.syncs, 0, 'in standby non si ridisegna niente');
    """, standby=True)


def test_the_shipped_switch_is_standby() -> None:
    """Pinna lo stato in cui si spedisce: si toglie quando l'arte c'e'."""
    assert _const(JENNY_JS.read_text(encoding="utf-8"), "MOOD_STANDBY") == "true"


# ── Il contratto con il backend ────────────────────────────────────────────────


def test_mood_art_covers_every_backend_label_except_neutral() -> None:
    art = _mood_art(JENNY_JS.read_text(encoding="utf-8"))
    expected = {m for m in MOODS if m != NEUTRAL_MOOD}
    assert set(art) == expected, (
        f"MOOD_ART (JS) e MOODS (Python) divergono: {sorted(art)} vs {sorted(expected)}"
    )


def test_mood_art_borrows_only_poses_that_exist_and_ship() -> None:
    """Niente arte nuova prevista: ogni posa presa in prestito è già un file e già nel manifest."""
    art = _mood_art(JENNY_JS.read_text(encoding="utf-8"))
    manifest = set(_UI_MANIFEST)
    for mood, url in art.items():
        rel = url.removeprefix("/html-mobile/")
        assert rel in manifest, f"{mood}: {rel} non è in _UI_MANIFEST"
        assert (ASSETS.parent / rel).is_file(), f"{mood}: {rel} non esiste"
