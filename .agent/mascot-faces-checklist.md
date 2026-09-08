# Le facce di Jenny — lista di esecuzione

Stato di [`mascot-faces-plan.md`](./mascot-faces-plan.md). Il ragionamento sta
là, qui c'è solo cosa è fatto. Si spunta quando è **girato** (test verdi, o
visto sul telefono per il passo 7), non quando è scritto.

Ramo `feat/mascot-faces`, aperto l'08/09/2026 da `main` (004a56b). **Passi 0 e
1 girati l'08/09/2026**: 9.211 test verdi, lint e pyright puliti. Il B/N è
uscito senza perdere pose (verificato prima con gli md5: tutte e 15 erano
davvero colorate) e la guida `COLORARE_LE_POSE.md` è diventata
`SOSTITUIRE_UNA_POSA.md`, perché di colorare non c'è più niente.

Verifica per ogni passo (da `AGENTS.md`, con la correzione locale
`python3 -m pytest`, non `pytest`):

```bash
ruff check jenny/ tests/ && npx pyright jenny/bus jenny/command jenny/runtime jenny/session && python3 -m pytest -q
```

Commit sempre con `-s` (DCO). Il telefono è su Python 3.11: le aree toccate
vanno provate anche nel venv 3.11 (`cat /tmp/py311/pyvenv.cfg` prima di
fidarsi — quel venv si è già rotto due volte).

---

## Passo 0 — il ramo

- [x] **0.1** albero pulito, `main` aggiornata
- [x] **0.2** `git switch -c feat/mascot-faces main`

## Passo 1 — il bianco/nero si ritira *(un commit)*

- [x] **1.1** asset: 15 webp B/N cancellati, 15 `-color` rinominati al nome
      piano; `_UI_MANIFEST` da 30 a 15 voci
- [x] **1.2** sorgenti: 15 `<stem>.PNG` line-art e `icon_color.png` cancellati,
      15 `<stem>_color.PNG` rinominati a `<stem>.PNG`
- [x] **1.3** `gen_pose_webp.py`: un export per posa
- [x] **1.4** `shared/mascot.js`: via `poseUrl`, `mascotColor`,
      `setMascotColor`, `COLOR_KEY`, `color` dal detail; chiave morta ripulita
- [x] **1.5** `mobile-jenny.js`: `poseUrl(x)` → `x`, e via il ciclo di
      `_applyMascotPrefs` sulle `img` del volo
- [x] **1.6** `mobile-onboarding.js`: `poseUrl(x)` → `x`
- [x] **1.7** `mobile-settings.js` + i18n `it`/`en`: via la riga dei colori
- [x] **1.8** test: `localStorage` con `jenny-mascotte-color = '0'` non produce
      path B/N e la chiave si ripulisce; parità i18n verde
- [x] **1.9** `themes-mascot.md`, `settings.md`, `README.md`,
      `COLORARE_LE_POSE.md`: una variante per posa
- [x] **1.10** verifica verde; commit `-s`

## Passo 2 — i sorgenti nuovi e la pipeline *(un commit, nessun cambio a runtime)*

- [ ] **2.1** 23 PNG da `JENNY_IMG_NEW/` a `android/image_source/` coi nomi
      della tabella (i due nomi sporchi corretti, `wave1`/`wave2` incrociati);
      `JENNY_IMG_NEW/` rimossa
- [ ] **2.2** `gen_pose_webp.py`: tabella `LAYERS`, 9 export
- [ ] **2.3** `_UI_MANIFEST`: +9
- [ ] **2.4** `README.md` + `COLORARE_LE_POSE.md`: due livelli, tabella,
      riserva, ricetta della coppia neutra diagonale
- [ ] **2.5** `tests/webui/test_mascot_layer_sources.py`: esistenza + manifest
      + **ricomposizione** dei tre pari cotti (`importorskip("PIL")`)
- [ ] **2.6** verifica verde; commit `-s`

## Passo 3 — il livello faccia nel client *(un commit, umore ancora fermo)*

- [ ] **3.1** CSS: `.jenny-art-stack`, `img.jenny-face`, specchio e volo
      spostati sul wrapper, `:not(.layered)` nasconde la faccia; bob e wobble
      non toccati
- [ ] **3.2** `_buildDom`: wrapper + seconda `img`; il volo resta fratello
- [ ] **3.3** `BODY`/`FACE`, `_setBody`/`_setFace`, classe `layered`
      (`out && !flying`)
- [ ] **3.4** `_syncArt` e `_talkTick` sulla precedenza del piano; ramo cotto
      invariato
- [ ] **3.5** preload dei 9
- [ ] **3.6** test: precedenza della faccia; ramo cotto invariato
- [ ] **3.7** verifica verde; commit `-s`

## Passo 4 — il vocabolario *(un commit, backend)*

- [ ] **4.1** `MOODS` a quattro, `_LETTER_TO_MOOD` A–D, riga `C` del prompt
- [ ] **4.2** `config/schema.py`: `mascot_mood` di default `True`
- [ ] **4.3** test `tests/session/` e `tests/config/` aggiornati
- [ ] **4.4** verifica verde, **anche su 3.11**; commit `-s`

## Passo 5 — l'umore sulle facce *(un commit, client)*

- [ ] **5.1** via `MOOD_ART`, `MOOD_STANDBY`, `MOOD_WORRY_AFTER_MS`,
      `_armWorry`, `_disarmWorry`, guardia `worried`
- [ ] **5.2** contratto `MOODS` ⊆ `FACE` in `test_mascot_mood_client.py`;
      harness senza standby né timer della preoccupazione
- [ ] **5.3** l'errore fa ancora `sad`
- [ ] **5.4** verifica verde; commit `-s`

## Passo 6 — documentazione *(stesso PR)*

- [ ] **6.1** `docs/using/themes-mascot.md`: quattro espressioni, due livelli;
      via lo standby
- [ ] **6.2** `docs/reference/configuration.md`: `mascotMood` torna `true`
- [ ] **6.3** `docs/reference/websocket.md`: etichette del frame
- [ ] **6.4** note di superamento su `mascot-mood-plan.md` (D7, D13, Standby) e
      sulla sua checklist (passo 7 → qui)
- [ ] **6.5** nessun file di `docs/` spostato o rinominato

## Passo 7 — sul telefono *(nessun codice; misure nel piano)*

- [ ] **7.1** APK dal ramo, albero pulito
- [ ] **7.2** screenshot: idle, pensa, parlato, un umore
- [ ] **7.3** parlato: bocca a 260 ms, gesto a 2,6 s, **faccia incollata al
      corpo**
- [ ] **7.4** specchio: da sinistra e out guarda dentro, alle tre taglie
- [ ] **7.5** docked identico a prima (confronto con screenshot pre-modifica)
- [ ] **7.6** volo: nessuna faccia addosso alla pegman
- [ ] **7.7** zero 404 in logcat dopo la rinomina, anche con `localStorage`
      che aveva il B/N scelto
- [ ] **7.8** Impostazioni: la riga dei colori non c'è più, le altre due vanno
- [ ] **7.9** turno in errore → faccia triste
- [ ] **7.10** turno da Telegram → la mascotte reagisce nella WebUI
- [ ] **7.11** onboarding con `workspace/` azzerato: Jenny ha la faccia
- [ ] **7.12** PR verso `main` (il merge è dell'utente)

## Passo 8 — l'interruttore *(dopo il 7)*

- [ ] **8.1** riga "Espressioni" in Personalizzazione → Mascotte, via
      `store.mutate()`
- [ ] **8.2** i18n `it`/`en`
- [ ] **8.3** `docs/reference/settings.md`
- [ ] **8.4** test di route; verifica verde; commit `-s`
