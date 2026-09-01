"""Un errore di rete non deve arrivare all'utente troncato ai due punti.

Misurato in produzione il 01/09/2026: la chat mostrava ``Error calling LLM:`` e
nient'altro, e logcat riportava la stessa stringa vuota
(``jenny.agent.loop:_run_agent_loop`` logga ``result.final_content``). La causa
non era il provider: ``str(exc)`` e' vuoto per *tutta* la famiglia dei timeout e
degli errori di connessione di httpx, che si costruiscono senza messaggio, e
``f"...: {exc}"`` non lascia niente dietro i due punti. Su un telefono quella
famiglia e' il caso normale, non il caso raro.

Il test guarda la promessa, non l'implementazione: qualunque strada porti un
errore fino all'utente, il messaggio deve nominare *qualcosa* di attribuibile.
"""

from __future__ import annotations

import asyncio

import httpx
import pytest

from jenny.providers.base import StreamTimeout, describe_exc

# La famiglia con ``str()`` vuoto. Non e' un elenco inventato: sono le
# eccezioni che httpx solleva su una rete mobile che cade, piu' StreamTimeout,
# che nel suo ``__init__`` chiama ``super().__init__()`` senza argomenti.
EMPTY_STR_EXCEPTIONS = [
    httpx.ReadTimeout(""),
    httpx.ConnectTimeout(""),
    httpx.WriteTimeout(""),
    httpx.PoolTimeout(""),
    httpx.ConnectError(""),
    httpx.RemoteProtocolError(""),
    httpx.ReadError(""),
    asyncio.TimeoutError(),
    StreamTimeout(30.0, saw_output=False),
    Exception(),
]


@pytest.mark.parametrize("exc", EMPTY_STR_EXCEPTIONS, ids=lambda e: type(e).__name__)
def test_the_premise_holds(exc):
    """Prima di tutto: queste eccezioni hanno davvero ``str()`` vuoto.

    Se httpx un giorno iniziasse a dare un messaggio di default, questo test
    fallirebbe e direbbe che la premessa del fix e' cambiata — meglio che
    scoprirlo da un altro test che passa per il motivo sbagliato.
    """
    assert str(exc) == "", f"{type(exc).__name__} non ha piu' str() vuoto"


@pytest.mark.parametrize("exc", EMPTY_STR_EXCEPTIONS, ids=lambda e: type(e).__name__)
def test_describe_exc_never_returns_empty(exc):
    described = describe_exc(exc)
    assert described, f"{type(exc).__name__} descritta con una stringa vuota"
    assert described == type(exc).__name__


@pytest.mark.parametrize("exc", EMPTY_STR_EXCEPTIONS, ids=lambda e: type(e).__name__)
def test_user_facing_message_is_not_truncated_at_the_colon(exc):
    """La forma esatta che l'utente ha visto, e che non deve piu' prodursi."""
    msg = f"Error calling LLM: {describe_exc(exc)}"
    assert msg != "Error calling LLM: "
    assert not msg.rstrip().endswith(":")
    assert type(exc).__name__ in msg


def test_a_real_message_is_left_alone():
    """Quando l'eccezione ha un messaggio, ``describe_exc`` non lo tocca.

    Il fix non deve sostituire un errore parlante col nome della sua classe:
    ``ProviderHTTPError`` nomina status, URL ed estratto del corpo, ed e' molto
    piu' utile di ``ProviderHTTPError``.
    """
    assert describe_exc(httpx.ConnectError("Cannot resolve hostname: hps")) == (
        "Cannot resolve hostname: hps"
    )
    assert describe_exc(RuntimeError("429 rate limited")) == "429 rate limited"
