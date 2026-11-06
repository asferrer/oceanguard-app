# Releasing

The detector is not stored in git, so a release is built locally, with
`android/app/src/main/assets/models/rtdetrv2_detector.tflite` in place:

```bash
cd android && ./gradlew assembleRelease && cd ..
cp android/app/build/outputs/apk/release/app-release.apk OceanGuard-AI-latest.apk
gh release create v1.0.0 --repo asferrer/oceanguard-app --title 'OceanGuard AI v1.0.0' \
  OceanGuard-AI-latest.apk android/app/src/main/assets/models/rtdetrv2_detector.tflite
```

`UpdateChecker` downloads `OceanGuard-AI-latest.apk` from the latest release, and
`scripts/setup-models.*` verify the detector against `models/CHECKSUMS.txt`.
