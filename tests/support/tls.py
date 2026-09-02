"""CA di prova per i test della fiducia TLS per provider.

Un certificato vero e non un file finto: ``ssl.SSLContext.load_verify_locations``
rifiuta tutto cio' che non e' un PEM, quindi un segnaposto proverebbe solo il
percorso d'errore. Usa pyca/``cryptography``, che nei test e' consentita — su
Android no, ma qui non ci siamo (v. ``tests/snapshot/test_cryptography_is_dev_only``).

Serve a *caricare* una fiducia, non a validare una catena: per un handshake vero
questa CA non basta (OpenSSL 3 pretende anche ``SubjectKeyIdentifier`` e
``KeyUsage``), ed e' apposta — la prova con handshake sta fuori dalla suite,
dove un server TLS finto non puo' far flakare la CI.
"""

from __future__ import annotations

import datetime
from pathlib import Path

CA_COMMON_NAME = "Jenny Test CA"


def write_test_ca(path: Path, *, common_name: str = CA_COMMON_NAME) -> Path:
    """Scrive in *path* un certificato CA autofirmato in PEM. Ritorna *path*."""
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name)])
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=1))
        .not_valid_after(now + datetime.timedelta(days=365))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return path
