"""Copertura per ``jenny.session.webui_turns``.

``tests/webui/test_webui_turn_helpers.py`` copre solo ``publish_turn_run_status``
(strip di timing). Qui si copre il resto del modulo: marcatura sessione WebUI e
``WebuiTurnCoordinator`` — il fan-out degli eventi runtime verso i messaggi
WebSocket della WebUI, compresa la proiezione dei turni di altri canali.
"""

from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

from jenny.bus.events import InboundMessage
from jenny.bus.queue import MessageBus
from jenny.bus.runtime_events import (
    RuntimeEventBus,
    RuntimeEventContext,
    RuntimeModelChanged,
    SessionTurnStarted,
    TurnCompleted,
    TurnRunStatusChanged,
)
from jenny.session import webui_turns as wt
from jenny.session.keys import HEARTBEAT_SESSION_KEY
from jenny.session.manager import Session, SessionManager
from jenny.session.turn_visibility import silent_turn_metadata

# --- mark_webui_session --------------------------------------------------------


def test_mark_webui_session_sets_metadata_when_opted_in():
    session = Session(key="websocket:c1")
    assert wt.mark_webui_session(session, {"webui": True}) is True
    assert session.metadata["webui"] is True


def test_mark_webui_session_noop_when_not_opted_in():
    session = Session(key="websocket:c1")
    assert wt.mark_webui_session(session, {"webui": False}) is False
    assert wt.mark_webui_session(session, {}) is False
    assert "webui" not in session.metadata


# --- WebuiTurnCoordinator ----------------------------------------------------------


def _coordinator(tmp_path) -> tuple[wt.WebuiTurnCoordinator, MessageBus, list]:
    bus = MagicMock()
    bus.publish_outbound = AsyncMock()
    sessions = SessionManager(tmp_path)
    scheduled: list = []

    def schedule_background(coro):
        scheduled.append(coro)

    coordinator = wt.WebuiTurnCoordinator(
        bus=bus,
        sessions=sessions,
        schedule_background=schedule_background,
    )
    return coordinator, bus, scheduled


def test_subscribe_registers_and_unsubscribe_removes_all_handlers(tmp_path):
    coordinator, _bus, _scheduled = _coordinator(tmp_path)
    runtime_events = RuntimeEventBus()

    unsubscribe = coordinator.subscribe(runtime_events)
    assert len(runtime_events._handlers) == 4

    unsubscribe()
    assert runtime_events._handlers == []


async def test_handle_session_turn_started_marks_webui_session(tmp_path):
    coordinator, _bus, _scheduled = _coordinator(tmp_path)
    ctx = RuntimeEventContext(
        channel="websocket",
        chat_id="c1",
        session_key="websocket:c1",
        metadata={"webui": True},
    )
    await coordinator._handle_session_turn_started(SessionTurnStarted(context=ctx))
    session = coordinator.sessions.get_or_create("websocket:c1")
    assert session.metadata["webui"] is True


async def test_handle_session_turn_started_ignores_non_websocket(tmp_path):
    coordinator, _bus, _scheduled = _coordinator(tmp_path)
    ctx = RuntimeEventContext(
        channel="cli",
        chat_id="c1",
        session_key="cli:c1",
        metadata={"webui": True},
    )
    await coordinator._handle_session_turn_started(SessionTurnStarted(context=ctx))
    # Nessuna sessione creata per un canale non websocket.
    assert "cli:c1" not in coordinator.sessions._cache


async def test_handle_run_status_changed_publishes_for_websocket_only(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    ctx = RuntimeEventContext(channel="websocket", chat_id="c1", session_key="websocket:c1")
    await coordinator._handle_run_status_changed(
        TurnRunStatusChanged(context=ctx, status="running", started_at=10.0),
    )
    bus.publish_outbound.assert_awaited_once()

    bus.publish_outbound.reset_mock()
    ctx_other = RuntimeEventContext(channel="cli", chat_id="c1", session_key="cli:c1")
    await coordinator._handle_run_status_changed(
        TurnRunStatusChanged(context=ctx_other, status="running"),
    )
    bus.publish_outbound.assert_not_awaited()


async def test_handle_runtime_model_changed_broadcasts(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    await coordinator._handle_runtime_model_changed(
        RuntimeModelChanged(model="m2", model_preset="fast", provider="prov-x"),
    )
    bus.publish_outbound.assert_awaited_once()
    call = bus.publish_outbound.await_args[0][0]
    assert call.chat_id == "*"
    assert call.metadata["model"] == "m2"
    assert call.metadata["model_preset"] == "fast"
    assert call.metadata["provider"] == "prov-x"


async def test_handle_turn_end_publishes_turn_end(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    msg = InboundMessage(channel="websocket", sender_id="u", chat_id="c1", content="hi")
    await coordinator.handle_turn_end(msg, latency_ms=120)

    bus.publish_outbound.assert_awaited_once()
    call = bus.publish_outbound.await_args[0][0]
    assert call.metadata["_turn_end"] is True
    assert call.metadata["latency_ms"] == 120


async def test_handle_turn_end_noop_for_non_websocket_channel(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    msg = InboundMessage(channel="cli", sender_id="u", chat_id="c1", content="hi")
    await coordinator.handle_turn_end(msg, latency_ms=None)
    bus.publish_outbound.assert_not_awaited()


async def test_handle_turn_completed_event_publishes_turn_end_and_schedules_nothing(tmp_path):
    """La fine di un turno WebUI è un solo frame, e nessun lavoro in background.

    Fino al 05/09/2026 qui partiva la generazione del titolo di sessione: una
    richiesta al modello dopo ogni turno finché il titolo non c'era, per un campo
    che nessuna vista leggeva. ``TurnCompleted.runtime`` resta sull'evento (è la
    foto del provider/modello del turno) ma il coordinatore non lo consuma più.
    """
    coordinator, bus, scheduled = _coordinator(tmp_path)
    ctx = RuntimeEventContext(
        channel="websocket",
        chat_id="c1",
        session_key="websocket:c1",
        metadata={"webui": True},
    )
    event = TurnCompleted(context=ctx, latency_ms=75, runtime=MagicMock())

    await coordinator._handle_turn_completed_event(event)

    assert scheduled == []
    bus.publish_outbound.assert_awaited_once()
    out = bus.publish_outbound.await_args[0][0]
    assert out.metadata["_turn_end"] is True
    assert out.metadata["latency_ms"] == 75
    assert not out.metadata.get("_session_updated")


async def test_handle_turn_completed_event_ignores_non_websocket(tmp_path):
    coordinator, bus, scheduled = _coordinator(tmp_path)
    ctx = RuntimeEventContext(channel="cli", chat_id="c1", session_key="cli:c1")
    event = TurnCompleted(context=ctx, latency_ms=None, runtime=None)

    await coordinator._handle_turn_completed_event(event)
    bus.publish_outbound.assert_not_awaited()
    assert scheduled == []


# --- proiezione dei turni esterni sulla vista WebUI --------------------------------


def _telegram_ctx(metadata: dict | None = None) -> RuntimeEventContext:
    return RuntimeEventContext(
        channel="telegram",
        chat_id="42",
        session_key="unified:default",
        metadata=metadata if metadata is not None else {"webui_turn_id": "t1"},
    )


def test_webui_view_target_routing():
    ws = RuntimeEventContext(channel="websocket", chat_id="c1", session_key="unified:default")
    assert wt.webui_view_target(ws) == ("websocket", "c1")
    assert wt.webui_view_target(_telegram_ctx()) == ("websocket", "default")
    internal = RuntimeEventContext(
        channel="internal", chat_id="x", session_key="unified:default"
    )
    assert wt.webui_view_target(internal) is None
    non_unified = RuntimeEventContext(
        channel="telegram", chat_id="42", session_key="cron:job1"
    )
    assert wt.webui_view_target(non_unified) is None


def test_a_silent_turn_has_no_webui_projection():
    """Il canale d'origine non basta a decidere.

    Un heartbeat o un cron monitor gira *su* ``websocket:default`` — è il target a
    cui consegnerà se una condizione scatta — ma spinner e ``_turn_end``
    appartengono alla conversazione dell'utente, non a un controllo che non ha
    chiesto. Senza questo gate ogni ciclo lasciava i propri marcatori in chat.
    """
    heartbeat = RuntimeEventContext(
        channel="websocket", chat_id="default", session_key=HEARTBEAT_SESSION_KEY
    )
    assert wt.webui_view_target(heartbeat) is None

    monitor = RuntimeEventContext(
        channel="websocket", chat_id="chat-1", session_key="cron:job-m"
    )
    assert wt.webui_view_target(monitor) is None

    marked = RuntimeEventContext(
        channel="websocket",
        chat_id="c1",
        session_key="unified:default",
        metadata=silent_turn_metadata(),
    )
    assert wt.webui_view_target(marked) is None


async def test_a_silent_turn_publishes_no_run_status(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    ctx = RuntimeEventContext(
        channel="websocket", chat_id="default", session_key=HEARTBEAT_SESSION_KEY
    )

    await coordinator._handle_run_status_changed(
        TurnRunStatusChanged(context=ctx, status="running", started_at=10.0),
    )
    await coordinator._handle_turn_completed_event(
        TurnCompleted(context=ctx, latency_ms=12, runtime=None),
    )

    bus.publish_outbound.assert_not_awaited()


async def test_external_turn_start_publishes_user_echo(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    event = SessionTurnStarted(context=_telegram_ctx(), content="ciao dal telefono")

    await coordinator._handle_session_turn_started(event)

    bus.publish_outbound.assert_awaited_once()
    call = bus.publish_outbound.await_args[0][0]
    assert call.channel == "websocket"
    assert call.chat_id == "default"
    assert call.content == "ciao dal telefono"
    assert call.metadata["_user_echo"] is True
    assert call.metadata["origin_channel"] == "telegram"
    assert call.metadata["webui_turn_id"] == "t1"


async def test_external_turn_start_skips_stop_and_empty(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    await coordinator._handle_session_turn_started(
        SessionTurnStarted(context=_telegram_ctx(), content="/stop")
    )
    await coordinator._handle_session_turn_started(
        SessionTurnStarted(context=_telegram_ctx(), content="   ")
    )
    bus.publish_outbound.assert_not_awaited()


async def test_external_turn_start_skips_internal_continuation(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    await coordinator._handle_session_turn_started(
        SessionTurnStarted(
            context=_telegram_ctx({"_internal_continuation": True}),
            content="continua il lavoro",
        )
    )
    bus.publish_outbound.assert_not_awaited()


async def test_external_turn_start_skips_internal_and_non_unified(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    internal = RuntimeEventContext(
        channel="internal", chat_id="x", session_key="unified:default"
    )
    await coordinator._handle_session_turn_started(
        SessionTurnStarted(context=internal, content="lavoro interno")
    )
    non_unified = RuntimeEventContext(
        channel="telegram", chat_id="42", session_key="cron:job1"
    )
    await coordinator._handle_session_turn_started(
        SessionTurnStarted(context=non_unified, content="x")
    )
    bus.publish_outbound.assert_not_awaited()


async def test_external_turn_completed_publishes_turn_end_on_view(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    event = TurnCompleted(context=_telegram_ctx(), latency_ms=99, runtime=None)

    await coordinator._handle_turn_completed_event(event)

    bus.publish_outbound.assert_awaited_once()
    call = bus.publish_outbound.await_args[0][0]
    assert call.channel == "websocket"
    assert call.chat_id == "default"
    assert call.metadata["_turn_end"] is True
    assert call.metadata["latency_ms"] == 99
    assert call.metadata["webui_turn_id"] == "t1"


async def test_external_run_status_projected_on_view(tmp_path):
    coordinator, bus, _scheduled = _coordinator(tmp_path)
    await coordinator._handle_run_status_changed(
        TurnRunStatusChanged(context=_telegram_ctx(), status="running", started_at=10.0),
    )
    bus.publish_outbound.assert_awaited_once()
    call = bus.publish_outbound.await_args[0][0]
    assert call.channel == "websocket"
    assert call.chat_id == "default"
    assert call.metadata["_goal_status"] is True
    assert call.metadata["goal_status"] == "running"


