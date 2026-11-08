# Photo contribution

Optional, off by default. When the user turns it on, each analysed photo is uploaded to the OceanGuard
contribution gateway (`contribution-api`, contract v3) to improve the detection and species models and for
statistics.

## What is sent

- The photo exactly as stored, **with its EXIF**: GPS location, date and time, make and model, orientation.
  With **Include location** off (Settings), a temporary copy loses every GPS tag and the XMP packet and that copy
  is sent; the photo on the phone is not modified. If the tags cannot be removed, the photo is not sent.
- The detections as COCO JSON (`CocoAnnotationSerializer`).
- The app version and an anonymous install id (`client_id`, a random UUID created on the first upload).

## Configuration (never in the repository)

`android/local.properties` (git-ignored) or environment variables of the same name:

```
CONTRIBUTION_API_URL=https://contrib.<domain>
CONTRIBUTION_API_KEY_ID=og-android-2026a
CONTRIBUTION_API_SECRET=<from contribution-api/deploy/generate_keys.py>
```

Without them the APK has no destination: the Settings switch is disabled with an explanation, the post-analysis
sheet never appears, nothing is queued and, at start-up, anything left in the queue is removed.

`android/app/google-services.json` (git-ignored) turns on Firebase App Check: the build applies the
google-services plugin, adds the App Check SDK (debug provider in debug builds, Play Integrity in release builds)
and attaches `X-Firebase-AppCheck` to every request. Without the file the APK contains no Firebase code.
`-Poceanguard.appcheck=true` compiles the App Check sources without the file, to check that they build.

## Security decisions

- **TLS**: OkHttp defaults (system trust store, hostname verification). No custom trust manager and no exceptions
  for private addresses. Only `https://` URLs are accepted, plus `http://` to `localhost`/`127.0.0.1` for tests.
- **Embedded HMAC secret**: it can be extracted from the APK, so it is not the main defence. It provides
  integrity, revocation by `key_id` (rotation without breaking old versions) and a cheap filter. The defence that
  cannot be copied from the APK is App Check; a public build must run against a gateway with
  `OG_APPCHECK_ENFORCE=true`. A build without `google-services.json` is only for local tests.
- **Consent is versioned** (`CONSENT_VERSION` in `data/settings/ContributionSettings.kt`). The consent of v1.1.1
  promised "no GPS coordinates"; it no longer counts, the user is asked again and what was queued under it is
  dropped.

## Queue and retries

`ContributionUploadRunner` sends one photo at a time:

| Gateway answer | Item | Pass |
|---|---|---|
| 201, or 200 duplicate | DONE | continues |
| 413 / 415 / 422, photo missing, location not removable | FAILED | continues |
| 401 (key or App Check), 5xx, network | stays PENDING, one attempt spent | stops, WorkManager retries (backoff 5 min, doubling) |
| 429 | stays PENDING, no attempt spent | stops, retried later |

After 5 attempts the item becomes FAILED, so no item stays PENDING forever. Settings shows the counts, the last
result in words ("the server rejected the app key…"), **Upload now**, **Retry failed**, the anonymous contributor
id, **Delete my contributed photos** (`POST /v1/withdrawals`), **Reset contribution prompts** and the privacy
policy (six languages).

## Tests

`ContributionSignerTest` (gateway HMAC vectors), `ContributionApiClientTest` (MockWebServer),
`ContributionConfigTest`, `ContributionImageTest` (GPS removal, Robolectric), `ContributionUploadRunnerTest`,
`ContributionRepositoryTest` (WorkManager testing), `DataContributionWorkerTest`, `ContributionConsentTest`.
`ContributionGatewayE2ETest` runs against a local gateway only when `OG_E2E_URL`, `OG_E2E_KEY_ID` and
`OG_E2E_SECRET` are set (see `contribution-api/deploy/CHECKLIST_AUTOR.md`, step 5).
