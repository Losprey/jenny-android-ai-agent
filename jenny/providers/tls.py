"""Fiducia TLS per singolo provider: una CA in piu', nominata dall'utente.

Le chiamate HTTP verso il modello le fa httpx dentro il runtime Python
impacchettato nell'APK, e quel runtime porta il **proprio** bundle di CA
(``certifi``). Lo store dei certificati di Android non lo consulta mai: chi
installa la propria CA sul telefono — la cosa giusta da fare per ogni altra app —
qui non ottiene niente, perche' nessuno sta guardando quello store. Da qui il
campo ``providers.providers[].caBundle``: si nomina un file PEM e le connessioni
a *quel* provider si fidano anche di quella CA.

Due scelte, entrambe volute:

* **La fiducia si aggiunge, non sostituisce.** Il contesto parte dal bundle di
  default (lo stesso ``certifi`` che httpx userebbe da solo) e ci somma la CA
  indicata. E' il modello mentale dello store utente di Android — si *aggiunge*
  un certificato — ed evita il guasto peggiore della sostituzione: un endpoint
  con certificato pubblico che smette di validare perche' qualcuno ha nominato
  una CA per un altro motivo.
* **Un file irraggiungibile e' un errore, non un ripiego.** Se il PEM manca, non
  si legge o non e' un PEM, si solleva :class:`CaBundleError`. Ricadere in
  silenzio sul bundle di default lascerebbe l'utente convinto di usare il proprio
  certificato mentre non lo usa — che e' esattamente lo stato da cui parte chi
  apre una segnalazione del genere.

Modulo foglia di proposito: prende la stringa di configurazione, non l'oggetto
config, cosi' lo possono chiamare sia ``providers/factory.py`` sia il livello
WebUI senza cicli di import.
"""

from __future__ import annotations

import ssl
from pathlib import Path

import httpx

__all__ = ["CaBundleError", "build_ssl_context", "resolve_ca_bundle"]


class CaBundleError(RuntimeError):
    """CA di provider nominata ma inutilizzabile.

    Sottoclasse di ``RuntimeError`` perche' e' cosi' che i chiamanti gia'
    trattano una configurazione di provider inservibile: ``GatewayContainer``
    cattura ``(ValueError, RuntimeError)`` attorno a ``make_provider`` e riparte
    senza provider invece di morire.
    """


def resolve_ca_bundle(raw: str) -> Path:
    """Percorso assoluto del PEM, con i relativi appesi al workspace.

    Un percorso relativo si risolve sulla radice del workspace perche' e' l'unico
    posto in cui l'utente puo' davvero far arrivare un file sul telefono: un
    allegato in chat finisce in ``workspace/uploads/``, e in alternativa si puo'
    chiedere a Jenny di scriverlo con ``write_file``. Un percorso assoluto passa
    invariato, per chi sa quel che fa.
    """
    path = Path(raw.strip()).expanduser()
    if not path.is_absolute():
        from jenny.config.paths import get_workspace_path

        path = get_workspace_path() / path
    return path


def build_ssl_context(
    ca_bundle: str | None, *, provider_name: str
) -> ssl.SSLContext | None:
    """Contesto SSL per un provider, o ``None`` se non c'e' niente da aggiungere.

    ``None`` non e' "nessuna verifica": significa che il chiamante non deve
    toccare ``verify``, e httpx resta sul suo default (``certifi``). E' il caso
    di ogni provider che non nomina una CA, cioe' quasi tutti.

    Raises:
        CaBundleError: se il file manca, non e' leggibile o non e' un PEM.
    """
    if not ca_bundle or not ca_bundle.strip():
        return None

    path = resolve_ca_bundle(ca_bundle)
    # Il punto di partenza e' *lo stesso* contesto che httpx costruirebbe da
    # solo con ``verify=True`` (``certifi``, o l'override di ``SSL_CERT_FILE``):
    # si aggiunge alla fiducia di default, non la si rimpiazza. Chiamare la
    # funzione di httpx invece di rifarla evita che le due nozioni di "default"
    # divergano alla prossima versione.
    context = httpx.create_ssl_context()
    try:
        context.load_verify_locations(cafile=str(path))
    except FileNotFoundError:
        raise CaBundleError(
            f"Provider '{provider_name}': CA bundle not found at {path}. "
            "Put the PEM file inside the workspace (a chat attachment lands in "
            "workspace/uploads/) and name it in the provider's caBundle."
        ) from None
    except OSError as exc:
        # Un solo handler perche' ``ssl.SSLError`` **e'** una ``OSError``: qui
        # cadono sia i permessi e la cartella-al-posto-del-file, sia il file che
        # c'e' ma non e' un certificato PEM.
        raise CaBundleError(
            f"Provider '{provider_name}': CA bundle at {path} could not be "
            f"loaded ({exc})."
        ) from exc
    return context
