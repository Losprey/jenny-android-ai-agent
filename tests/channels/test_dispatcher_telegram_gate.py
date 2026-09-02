"""Test del gate di ``WebSocketDispatcher._init_telegram`` e di cosa logga.

Il gemello di ``test_dispatcher_ws_enabled_flag.py`` per l'altro canale, con in
piu' le due righe di log. Prima ``_init_telegram`` usciva in silenzio in
entrambi i casi di rifiuto, e il costo si e' visto il 02/09/2026: un canale
spento e un bot che tace sono indistinguibili dall'esterno, e nei log non c'era
niente che dicesse quale dei due fosse.
"""

from __future__ import annotations

from loguru import logger as loguru_logger

from jenny.bus.queue import MessageBus
from jenny.channels.dispatcher import WebSocketDispatcher
from jenny.config.schema import Config

TOKEN = "123456789:AAtestTOKENtestTOKENtestTOKEN"


def _dispatch_with(**telegram: object) -> tuple[WebSocketDispatcher, str]:
    """Costruisce il dispatcher col solo ramo Telegram, catturando i log INFO.

    La sezione ``websocket`` resta vuota: ``_init_channel`` esce subito su una
    sezione falsy, cosi' il test non tira su il gateway per parlare di Telegram.
    """
    config = Config.model_validate({"telegram": telegram})
    records: list[str] = []
    handler_id = loguru_logger.add(lambda m: records.append(str(m)), level="INFO")
    try:
        dispatcher = WebSocketDispatcher(config, MessageBus())
    finally:
        loguru_logger.remove(handler_id)
    return dispatcher, "\n".join(records)


def test_enabled_with_token_builds_channel() -> None:
    dispatcher, log = _dispatch_with(enabled=True, botToken=TOKEN)

    assert "telegram" in dispatcher.channels
    assert "Telegram channel enabled" in log


def test_disabled_skips_channel_and_says_so() -> None:
    """Il caso misurato sul telefono: token a posto, ``enabled`` a false."""
    dispatcher, log = _dispatch_with(enabled=False, botToken=TOKEN)

    assert "telegram" not in dispatcher.channels
    assert "telegram.enabled=false" in log


def test_missing_token_is_reported_as_not_configured() -> None:
    """«Spento» e «non configurato» portano a due rimedi diversi.

    Il toggle nelle impostazioni oppure il token da BotFather: un log solo per
    i due casi lascerebbe da indovinare quale.
    """
    dispatcher, log = _dispatch_with(enabled=True)

    assert "telegram" not in dispatcher.channels
    assert "not configured" in log
    assert "telegram.enabled=false" not in log
