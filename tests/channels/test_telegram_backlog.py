"""Test dello smaltimento del backlog all'avvio del canale Telegram.

``get_updates`` senza offset restituisce tutto cio' che Telegram trattiene, e
``_offset`` riparte da ``None`` a ogni costruzione del canale — cioe' a ogni
avvio del gateway e a ogni toggle. Qui si fissa il confine fra i due casi che
contano: il messaggio scritto a cavallo di una ripartenza va lavorato, il
backlog di giorni no.
"""

from __future__ import annotations

import asyncio
import time
from typing import Any

from jenny.bus.queue import MessageBus
from jenny.channels.telegram import _BACKLOG_MAX_AGE_S, TelegramChannel
from jenny.config.schema import TelegramConfig

CHAT = "21824351"


def _update(update_id: int, *, age_s: float, text: str) -> dict[str, Any]:
    return {
        "update_id": update_id,
        "message": {
            "chat": {"id": int(CHAT)},
            "from": {"id": int(CHAT)},
            "date": int(time.time() - age_s),
            "text": text,
        },
    }


class ScriptedAPI:
    """``get_updates`` che serve batch prefissati, poi si blocca.

    Il blocco finale imita il long-poll: il loop resta appeso lì invece di
    girare a vuoto, e il test lo cancella quando ha visto quel che gli serve.
    """

    def __init__(self, batches: list[list[dict[str, Any]]]) -> None:
        self.batches = list(batches)
        self.offsets: list[int | None] = []
        self.drained = asyncio.Event()

    async def get_updates(self, offset, timeout_s):
        self.offsets.append(offset)
        if self.batches:
            return self.batches.pop(0)
        self.drained.set()
        await asyncio.sleep(3600)

    async def close(self) -> None:
        return None


async def _run(batches: list[list[dict[str, Any]]]) -> tuple[list[str], ScriptedAPI]:
    """Fa girare il poll loop sui batch dati; ritorna i contenuti pubblicati."""
    api = ScriptedAPI(batches)
    bus = MessageBus()
    channel = TelegramChannel(
        TelegramConfig(enabled=True, bot_token="t", paired_chat_id=CHAT),
        bus,
        api=api,  # type: ignore[arg-type]
    )
    task = asyncio.create_task(channel._poll_loop())
    await asyncio.wait_for(api.drained.wait(), timeout=5)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass

    published: list[str] = []
    while not bus.inbound.empty():
        published.append(bus.inbound.get_nowait().content)
    return published, api


async def test_stale_backlog_is_dropped_on_startup() -> None:
    """Il caso che ha motivato tutto: giorni di coda, riaccensione, raffica."""
    old = _BACKLOG_MAX_AGE_S + 3600
    published, _ = await _run([[
        _update(1, age_s=old, text="vecchio uno"),
        _update(2, age_s=old, text="vecchio due"),
    ]])

    assert published == []


async def test_message_across_a_restart_survives() -> None:
    """Il motivo per cui non si scarta il backlog in blocco.

    Su Android le ripartenze sono ordinarie: un riavvio pochi secondi dopo che
    l'utente ha scritto non deve perdere quel messaggio.
    """
    published, _ = await _run([[_update(1, age_s=3, text="ciao")]])

    assert published == ["ciao"]


async def test_dropped_updates_still_advance_the_offset() -> None:
    """Senza questo, uno scarto tornerebbe a ogni poll per sempre.

    L'offset va avanzato *prima* di decidere lo scarto: e' la conferma verso
    Telegram, e senza di essa il backlog non si smaltisce mai.
    """
    old = _BACKLOG_MAX_AGE_S + 3600
    _, api = await _run([[_update(7, age_s=old, text="vecchio")]])

    assert api.offsets[0] is None  # avvio a freddo: nessun offset
    assert api.offsets[1] == 8  # 7 + 1, nonostante lo scarto


async def test_filter_stops_after_the_first_fresh_message() -> None:
    """Il filtro non deve poter mangiare traffico vivo a vita.

    Un messaggio recente chiude lo smaltimento; da lì anche un update con una
    data vecchia (orologio sballato, o un inoltro) viene lavorato.
    """
    old = _BACKLOG_MAX_AGE_S + 3600
    published, _ = await _run([
        [_update(1, age_s=old, text="scartato"), _update(2, age_s=1, text="recente")],
        [_update(3, age_s=old, text="passa comunque")],
    ])

    assert published == ["recente", "passa comunque"]


async def test_empty_first_batch_ends_draining() -> None:
    """Coda vuota all'avvio: non c'era backlog, e il filtro si spegne subito."""
    old = _BACKLOG_MAX_AGE_S + 3600
    published, _ = await _run([[], [_update(1, age_s=old, text="dopo il vuoto")]])

    assert published == ["dopo il vuoto"]


async def test_update_without_a_readable_date_is_processed() -> None:
    """Eta' non deducibile: si lavora.

    Scartare e' irreversibile, quindi il caso ambiguo non lo merita.
    """
    published, _ = await _run([[{
        "update_id": 1,
        "message": {"chat": {"id": int(CHAT)}, "from": {"id": int(CHAT)}, "text": "senza data"},
    }]])

    assert published == ["senza data"]
