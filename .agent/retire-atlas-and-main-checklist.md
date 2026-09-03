# Togliere Atlas e `main` — lista di esecuzione

Stato di [`retire-atlas-and-main-plan.md`](./retire-atlas-and-main-plan.md). Il
ragionamento sta la', qui c'e' solo cosa e' fatto. Si spunta quando e' **girato**:
per una garanzia che vive in un prompt «girato» include la calibrazione su due
modelli in una sessione fresca.

Ramo: `feat/retire-atlas-and-main`. Scritto il 03/09/2026. **Fatti lo stesso giorno: 0, A, D, B, C, E, F, G** (tranne G.5 e G.7, che vogliono una sessione fresca e un secondo modello) e H.2; H.1 fatta (PR #30), H.3 gia' in mano all'utente. Un difetto trovato al primo avvio e chiuso: v. il piano, «Com'e' finita». Note sui passi:

- **A.4** misurata sul Mac: 0,25 ms (mediana) su 10 wiki e 400 pagine, 1.137 caratteri di blocco.
- **D4.2** risolto: una chiave `atlas:` residua oggi cade su `user` per il fallthrough di sempre, non su `system` — ma dopo B non ne esiste piu' nessuna sorgente, e il test misura il bucket legacy, non la chiave.
- **D5.1** fatto dentro B (il prefisso non poteva cadere finche' i test di Atlas lo usavano).
- **B.10**: i quattro test di `_prune_sessions` di Atlas non sono stati portati sul giardiniere: il giardiniere ha gia' i suoi (T2.5, T1.4 in `test_gardener.py`); resta il caso «la potatura sgombera anche il quarto registro» con una chiave del giardiniere.
- **G.3**: verificato via `/api/settings` (workers = gardener + compattazione), non dal pannello a schermo; il pannello token non riguardato.
- **G.4**: «quali wiki hai?» su sessione calda ha risposto dalla cronologia (5 nomi); «copia il blocco» ha reso le 13 righe esatte, zero tool. E' la domanda giusta.
- **G.9**: `/atlas` non e' piu' un comando: come ogni `/qualcosa` sconosciuto arriva al modello come testo, che risponde che non esiste (3,8 s, un turno). Non c'e' un «unknown command» testuale, e non c'era prima.
- **G.8**: misurato sul Mac (A.4), non con un log DEBUG sul telefono.
- **F.1**: oltre alle due occorrenze di D e ai loro test, la parola compare come **nome del file del piano** (`context.py`, `test_context_wikis.py`) e in tre frasi dei docs che dicono all'utente cosa e' stato ritirato (`memory.md`, `privacy.md`, `architecture.md`).

---

## Passo 0 — prima di toccare codice

- [x] **0.1** Snapshot manuale sul telefono (Impostazioni → Backup → *Crea snapshot ora*) e `tar` di `wikis/main` sul Mac: e' l'unico modo di rivedere `main` dopo
- [x] **0.2** `md5sum` di `memory/MEMORY.md`, `memory/history.jsonl`, `config.json` via root: la spazzata D3 e la riscrittura D2 si giudicano contro questi
- [x] **0.3** Ramo aperto, primo commit firmato (`git commit -s`)

## Passo A — il blocco `## Wikis` *(Atlas ancora vivo)*

- [x] **A.1** `ContextBuilder`: il blocco sotto `# Memory`, stesso cancello di `## Wiki Directory` (no `project:`, no `gardener:`), ordine alfabetico, `(no scope set)` stampato, nessun conteggio pagine, tetto costante, niente con `wiki.enabled = false`
- [x] **A.2** Test `tests/agent/test_context_wikis.py`: presente in personale con ogni wiki; assente in progetto e giardiniere **per nome di wiki** e non solo per intestazione; segnaposto → `(no scope set)`; `wiki.enabled = false` → niente; due build identiche; troncato al tetto; un solo `# Memory` con `MEMORY.md` template
- [x] **A.3** Un test transitorio: le wiki nominate da `## Wikis` sono le stesse della sezione `## Wikis` di un `WIKI.md` prodotto da `AtlasStore.build_inventory` sullo stesso albero. **Si cancella al passo B** — esiste per questo commit solo
- [x] **A.4** Misura: durata del blocco su dieci wiki finte con `AGENTS.md`, in un test che stampa e non asserisce; annotata nel piano

## Passo D — le ritirate *(col codice vecchio ancora presente, per produrre lo stato vero)*

- [x] **D1.1** `CronService.retire_system_job(job_id)`: rimuove anche un `system_event`, pota `cron/runs/<id>_*`, ritorna se ha tolto qualcosa
- [x] **D1.2** `GatewayContainer.build`: `_RETIRED_SYSTEM_JOBS = ("atlas",)` ritirati **prima** di registrare i vivi; un INFO per job tolto
- [x] **D1.3** Test: store con il job protetto → dopo `build` assente, run assenti, nessun warning al tick; un job **utente** con `name="atlas"` e id diverso resta
- [x] **D2.1** `config/loader.py`: `RETIRED_KEY_PATHS = frozenset({"agents.defaults.atlas", "wiki.defaultWiki", "wiki.default_wiki"})`; `_unknown_key_paths` le salta, `_merge_unknown` non le riporta
- [x] **D2.2** `CURRENT_CONFIG_VERSION = 2`, passo v2 in `_migrate_by_version` senza valori (solo il log una volta: «retired keys dropped»)
- [x] **D2.3** Test: config con entrambe le chiavi → nessun warning, dopo `mutate` assenti, `configVersion: 2`; una chiave ignota accanto **avvisa e sopravvive**; un config gia' a v2 senza le chiavi non viene riscritto
- [x] **D3.1** Spazzata all'avvio accanto a `_migrate_wikis`: `memory/WIKI.md`, `memory/.atlas_state.json`, `sessions/atlas_*.jsonl`; elenco chiuso, idempotente, un INFO per file
- [x] **D3.2** Test: i tre spariscono; `memory/WIKI_POLICY.md` e un file qualunque dell'utente restano; seconda passata: niente tolto, niente loggato
- [x] **D4.1** `token_usage.py`: `"atlas"` via da `_INTERNAL_KIND_TO_SOURCE`, resta in `_SOURCE_KEYS` col commento «bucket legacy: la storia non si rietichetta»
- [x] **D4.2** Test: uno stato con un giorno `atlas` si normalizza identico; una chiave `atlas:` di sessione oggi cade su `user`? **No** — su `system`, perche' non e' piu' un kind interno noto: il test dice quale dei due e' voluto (e' `system`: lavoro non attribuibile)
- [x] **D5.1** `keys.py`: via `ATLAS_SESSION_PREFIX` e la riga del vocabolario; docstring aggiornate; `test_internal_key_vocabulary.py` allineato

## Passo B — via Atlas

- [x] **B.1** Cancellati `jenny/agent/atlas.py`, `jenny/templates/agent/atlas.md`; via da `_SYSTEM_PROMPT_TEMPLATES`
- [x] **B.2** Cancellati i 7 test di Atlas
- [x] **B.3** `schema.py`: via `AtlasConfig` e il campo; `container.py`: via la registrazione; `cron_dispatch.py`: `_SYSTEM_WORKERS`, `_dispatch`, `_run_atlas`
- [x] **B.4** `worker_settings.py` + `settings_routes.py`: via payload, rearm, import; `mobile-settings.js` + i18n `it`/`en`: via la scheda e le sei chiavi; `api-client.js`, `battery-exemption.js`: commenti
- [x] **B.5** `command/`: via `cmd_atlas`, `_format_atlas_outcome`, le due route, la voce in `specs.py`, la menzione in `scope.py`; `tools/cron.py`: via la riga di `_system_job_purpose`
- [x] **B.6** `context.py`, `loop.py`, `memory.py`: via `wiki_directory_max_tokens`, `_DEFAULT_WIKI_DIRECTORY_TOKENS`, `wiki_file`, `read_wiki_memory`, `get_wiki_memory_context`, il vecchio blocco; `autocompact.py`: solo Dream nei prefissi
- [x] **B.7** `wiki_paths.py`: via `wiki_fingerprint`, `_stat_line`, `iter_wiki_sources` e i loro test
- [x] **B.8** Template `dream.md`, `dream_review.md`: via le frasi su `WIKI.md`; `skills/memory/SKILL.md`: descrizione e due righe riscritte sul blocco
- [x] **B.9** Commenti-analogia riscritti (elenco nel piano, §B): **zero** rimandi a codice che non c'e' piu'
- [x] **B.10** Test-fixture spostati su giardiniere/Dream: eviction, schedule-survives-restart, store-recovery, prompt-matches-offered-tools, container-registers, worker-settings ×3, settings-bounds, settings-routes, token-usage, turn-visibility, command-scope, gardener-settings-reach-dispatcher, cron-dispatch-gardener, Dream ×3. Il **caso** resta, il nome cambia
- [x] **B.11** I tre test su `## Wiki Directory` passano a `## Wikis` **e** al nome di una wiki
- [x] **B.12** Via il test transitorio A.3

## Passo C — via `main` dal codice

- [x] **C.1** `WikiConfig.default_wiki` tolto; `wiki_routes.py`: via il caso speciale e `defaultWiki` dal payload (nessun client lo legge: verificato con grep su `templates/ui/`)
- [x] **C.2** Esempi in docstring/SKILL con nome neutro: `python_exec_builtins.py:480`, `skills/llm-wiki/SKILL.md` 52 e 83, `reindex_wikis.py:16`

## Passo E — docs e `.agent`

- [x] **E.1** `docs/using/memory.md`: H1 *Memory and Dream*, via le sezioni Atlas, un paragrafo sul blocco
- [x] **E.2** `docs/using/wiki.md`, `projects.md` (link e ancora), `scheduling.md`, `slash-commands.md`, `gardener.md`, `chat.md`, `telegram.md`, `README.md`
- [x] **E.3** `docs/internals/architecture.md`, `concepts.md`, `agent-turn.md`, `privacy.md`
- [x] **E.4** `docs/reference/configuration.md` (atlas ×3, `wiki.defaultWiki`), `settings.md`, `tools.md`
- [x] **E.5** `.agent/security.md` 35 e 78; `.agent/memory-probes.md`: via la quinta sonda, titolo a quattro
- [x] **E.6** Nessun file di `docs/` spostato o rinominato (il sito deriva gli URL dal percorso)

## Passo F — accettazione statica

- [x] **F.1** `grep -ri atlas jenny/ tests/ docs/` → solo il pattern di D3, il bucket di D4 e i loro test
- [x] **F.2** `grep -rn "default_wiki\|defaultWiki" jenny/ tests/ docs/` → zero
- [x] **F.3** `grep -rn "WIKI\.md" jenny/ tests/ docs/` → zero
- [x] **F.4** `ruff check jenny/ tests/` · `npx pyright jenny/bus jenny/command jenny/runtime jenny/session` · `pytest -q` (con l'ambiente della memoria *local-build-and-test-env*)
- [x] **F.5** Versione alzata in `pyproject.toml` **e** `android/app/build.gradle.kts` (minor); `tests/test_package_version.py` verde

## Passo G — sul telefono

- [x] **G.1** Build a worktree pulito, `installDebug`, riavvio; logcat: «retired system job atlas», i tre file tolti, nessun «Config keys not recognised», nessun `avc: denied`
- [x] **G.2** Via root: `cron/jobs.json` senza `atlas`; `memory/` senza `WIKI.md` e `.atlas_state.json`; `sessions/` senza `atlas_*`; `config.json` a v2 senza le due chiavi, `.bak` con la label MLS; md5 di `MEMORY.md` e `history.jsonl` **uguali** a 0.2
- [x] **G.3** Impostazioni: due lavoratori; pannello token: i giorni vecchi invariati
- [x] **G.4** In chat, sessione fresca: *«quali wiki hai?»* → elenco esatto, zero tool call
- [ ] **G.5** In chat: un soggetto con pagina → apre l'indice della wiki prima di rispondere; un nome inesistente → `grep wikis` e un no
- [x] **G.6** Dentro un progetto: *«quali altre wiki ho?»* → non le sa dal prompt, le cerca
- [ ] **G.7** G.4–G.6 rifatti con un **secondo modello** (v. `memory-probes.md`: una promessa che regge su un lettore solo non e' una promessa)
- [x] **G.8** La misura di A.4 sul telefono, dal log DEBUG, poi il log tolto
- [x] **G.9** `/atlas` → comando sconosciuto

## Passo H — chiusura

- [x] **H.1** PR su `main` con la descrizione che punta al piano; merge: lo decide l'utente
- [x] **H.2** «Com'e' finita» scritto nel piano: scarti da qui, misure di G, calibrazione (cosa e' diventato rosso togliendo ogni garanzia)
- [ ] **H.3** Lo smontaggio dei **dati** di `main`: `roadmap/smontaggio-main.md`, con `## Wikis` a fare da cruscotto
