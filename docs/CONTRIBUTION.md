# Photo contribution

Optional, off by default. When the user turns it on, each analysed photo is sent for research
(better detection and species models, statistics). Pipeline and contract:
`OceanguardAI-contrib/contribution-api` (contract v4):

```
phone --HTTPS--> Cloudflare Worker (workers.dev, free) + D1 buffer --(outbound pull)--> research NAS
```

## What is sent

- The photo **at the resolution it has**, **with its EXIF**: GPS location, date and time, make and
  model, orientation. The in-app camera shoots in maximum-quality mode at the highest 4:3
  resolution the sensor offers (JPEG quality 100); a photo picked from the gallery is the stored
  copy, byte for byte. With **Include location** off (Settings), a temporary copy loses every GPS
  tag and the XMP packet and that copy is sent; the photo on the phone is not modified. If the
  tags cannot be removed, the photo is not sent.
- **Not a JPEG** (HEIC, PNG, WebP from the gallery) or beyond the NAS limits (40 MiB, 120 MP): the
  photo is transcoded at full resolution to JPEG quality 95, copying its EXIF (GPS only if
  location is allowed). If it is still over the limit the quality drops (90, 85, 80) and only
  then is it shrunk, as little as possible; `outputs.image.downscaled` records it and the COCO
  boxes are rescaled to what was sent (`ContributionImage`).
- The detections as COCO JSON (`CocoAnnotationSerializer`, unchanged; empty for a species photo)
  and the app version.
- **What the app answered** for that photo (`outputs`, below): detections and species
  identifications and the model that produced them.
- All of it **encrypted on the phone to the NAS public key** (`ContributionEnvelope`: ECIES
  P-256, HKDF-SHA256, AES-256-GCM). The Cloudflare buffer only holds ciphertext.
- An anonymous contributor id: the hash of a per-install P-256 key generated in the Android
  Keystore (`KeystoreDeviceIdentity`), which never leaves the phone.

Debris and **species** photos use the same consent and the same queue. A species identification
is queued automatically after it is saved to the BioDex when consent was given; otherwise the
history card of that identification offers the same upload button as a debris photo.

## Envelope meta and `outputs`

The meta keeps `"v": 1` and `annotations` (COCO) so older pullers keep working; `outputs` is an
additional key (`ContributionOutputs`, schema 1). Only what the app really has is included; a
field the app does not know is omitted, never invented. **No location and no device data** go in
`outputs` (the location stays in the EXIF, and only if the user allows it).

```json
{"v": 1, "contribution_id": "...", "app_version": "1.2.0", "annotations": { "...COCO..." },
 "outputs": {
   "schema": 1,
   "image": {"width": 4000, "height": 3000, "sha256": "<of the uploaded bytes>",
             "transcoded": false,
             "downscaled": true, "source_width": 16000, "source_height": 12000},
   "detection": {"model": "RT-DETRv2 + Gemma 4 E2B", "processing_ms": 812, "image_quality": "GOOD",
                 "items": [{"class": "BOTTLE", "score": 0.83, "bbox_norm": [x, y, w, h],
                            "bbox_px": [x, y, w, h], "subtype": "PLASTIC_BAG", "material": "PLASTIC",
                            "raw_label": "plastic_bag", "source": "detector|gemma"}]},
   "species": [{"species_key": "126436", "scientific_name": "Thunnus thynnus",
                "id_source": "RAG_CORE|RAG_TENTATIVE|VLM_OPENVOCAB|UNIDENTIFIED", "confidence": 0.91,
                "candidates": [{"species_key": "...", "score": 0.8}], "bbox_px": [x, y, w, h],
                "pack_version": 1, "geo_match_level": "PROVINCE", "out_of_range": true}]}}
```

- `image`: dimensions are those of the stored pixels (not rotated by EXIF, the frame the COCO boxes
  use); `sha256` is of the uploaded bytes; `downscaled`, `source_width` and `source_height` only
  when the photo had to be shrunk. `source` (camera or gallery) is not recorded: the app does not
  keep it per photo.
- `detection` (debris photos): `model` is the model that really ran (it can differ from the chosen
  mode); `processing_ms` is the whole analysis time. `bbox_norm` is in [0, 1] and `bbox_px` is it
  times the sent dimensions (added at upload). `source` is `gemma` when the label came from the
  open-vocabulary model (`raw_label`), `detector` otherwise. The analysis mode, threshold and a
  user-edited flag are not stored per photo and so are not sent.
- `species` (species photos, all answers of the run, also tentative and unidentified ones):
  `species_key` is the AphiaID, absent for uncatalogued answers; `bbox_px` is in pixels of the
  analysed frame; `candidates`, `pack_version` appear only when the identification result had
  them (not when the photo is sent later from the history, which keeps neither). The free-text
  description stays on the phone.
- Reports are written over several sessions, not per photo, so there is no per-photo `report`.

## Configuration (never in the repository)

`android/local.properties` (git-ignored) or environment variables of the same name; both values
are public:

```
CONTRIBUTION_API_URL=https://oceanguard-contrib.oceanguard-ai.workers.dev
CONTRIBUTION_RECIPIENT_KEY=<NAS public key from contribution-api/deploy/generate_keys.py>
```

**Attestation without Google services.** The Keystore key is generated with an attestation
challenge. Before its first request (and whenever the Worker answers `unregistered`) the app
sends the key's certificate chain to `POST /v1/devices`; the Worker checks it up to a Google
attestation root and requires the key in TEE or StrongBox, a locked bootloader with verified
boot, and this app (`com.oceanguard.ai`) signed with the author's release certificate. It
works for the APK distributed through GitHub: no Firebase, no Play Console, no quota. A phone
that fails (rooted, unlocked, no secure hardware) cannot contribute; Settings says why.

Without a destination, the Settings switch is disabled with an explanation, the post-analysis
sheet never appears, nothing is queued and anything left in the queue is removed at start-up.

## Security decisions

- **No secret in the APK.** No HMAC, no API key, no Firebase. Identity and attestation = the
  Keystore key (Android key attestation once, ECDSA signature on every request). The Worker only
  holds public keys.
- **End-to-end encryption** to the NAS: a compromise of the Cloudflare account or a TLS
  interception reveals no photo and no location.
- **TLS**: system trust store, TLS 1.2+ with restricted suites (`ConnectionSpec.RESTRICTED_TLS`),
  no cleartext, no redirects. **No certificate pinning**: Cloudflare rotates the CAs of
  workers.dev and advises against pinning; the end-to-end encryption makes it unnecessary.
- **Consent is versioned** (`CONSENT_VERSION` in `data/settings/ContributionSettings.kt`). The
  consent of v1.1.1 promised "no GPS coordinates"; it no longer counts, the user is asked again
  and what was queued under it is dropped.

## Queue and retries

`ContributionUploadRunner` sends one photo at a time. The contribution id is stable per queue
item and install, so a retry after a lost answer is a duplicate, not a second copy.
`ContributionApiClient` sends the envelope in parts of 1 900 000 bytes (one D1 row each, the
Worker's limit is 2 000 000 bytes per row) and then a commit; photos up to 40 MiB (a 108 MP
JPEG) are accepted; larger ones are re-encoded first (see above), and an envelope that is still
over the limit is marked FAILED without being sent. The EXIF is never
touched by the splitting: the parts are slices of the encrypted envelope.

| Worker answer | Item | Pass |
|---|---|---|
| 201, or 200 duplicate | DONE | continues |
| 411 / 413 / 415 / 422, photo missing, location not removable, not sealable | FAILED | continues |
| 401 (attestation refused or signature), 5xx, network | stays PENDING, one attempt spent | stops, WorkManager retries (backoff 5 min, doubling) |
| 409 (upload interrupted) | stays PENDING, one attempt spent | stops, the next attempt starts at part 0 |
| 429 or 503 quota | stays PENDING, no attempt spent | stops, retried later |

After 5 attempts the item becomes FAILED, so nothing stays PENDING forever. Settings shows the
counts, the last result in words, **Upload now**, **Retry failed**, the anonymous contributor
id, **Delete my contributed photos** (deletes what is still buffered at once; the NAS deletes
the rest on its next pull; the Keystore key is then deleted, so later contributions use a new
identity), **Reset contribution prompts** and the privacy policy (six languages).

## Tests

`ContributionEnvelopeTest` (same bytes as the NAS vector), `ContributionSignerTest`,
`ContributionApiClientTest` (MockWebServer; verifies the signature the way the Worker does),
`ContributionConfigTest`, `ContributionImageTest` (GPS removal, Robolectric),
`ContributionUploadRunnerTest`, `ContributionRepositoryTest` (WorkManager testing), `ContributionOutputsTest`, `CaptureConfigTest`,
`Migration15To16Test` (queue column `outputsJson`),
`DataContributionWorkerTest`, `ContributionConsentTest`. `ContributionGatewayE2ETest` runs
against the local Worker (`contribution-api/worker/local/serve.mjs`) only when `OG_E2E_URL` and
`OG_E2E_RECIPIENT_KEY` are set (see `contribution-api/deploy/CHECKLIST_AUTOR.md`, step 5).
