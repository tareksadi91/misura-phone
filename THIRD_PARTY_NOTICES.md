# Third-party inventory

Misura's [Apache-2.0 license](LICENSE) does not relicense dependencies, fonts, map data, photographs or service responses.

This inventory comes from `misura-app/gradle/libs.versions.toml`, module build files, source imports and the bundled font's name table. Dependency license labels below are an initial inventory, **not a verified resolved-artifact notice bundle**. The offline evidence below covers a subset of the release artifacts. Before distributing APKs, verify the exact resolved artifacts, transitive/native licenses, copyright notices and upstream NOTICE requirements, and bundle required texts. Do not treat this list as license clearance.

## Runtime and bundled components

| Component | Declared version | License / evidence and disposition |
| --- | --- | --- |
| AndroidX Core KTX | 1.19.1 | Apache-2.0; verify resolved artifact notices. |
| AndroidX Activity Compose | 1.13.0 | Apache-2.0; verify resolved artifact notices. |
| Compose UI, Material 3 | BOM 2026.09.00 | Apache-2.0; BOM selects actual module versions, not resolved here. |
| AndroidX Room runtime/KTX | 2.8.5 | Apache-2.0; verify transitive artifacts. |
| Kotlin standard library | Kotlin 2.4.21 | Apache-2.0; verify resolved artifacts. |
| kotlinx.serialization JSON | 1.12.0 | Apache-2.0. |
| kotlinx.coroutines core/Android | 1.11.0 | Apache-2.0. |
| MapLibre Android SDK | 13.6.1 | BSD-2-Clause at project level; bundled native components have additional notices. Exact archive audit remains open. |
| Shizuku API/provider | 13.1.5 | The cached 13.1.5 POMs for api, provider, aidl and shared name the MIT License. Their AARs and nested classes.jar files contained no license or notice files by filename. Obtain the applicable copyright and permission notices before APK distribution. The separately installed Shizuku app/fork is not shipped in this tree. |
| Hanken Grotesk (`app/src/main/res/font/hanken_grotesk.ttf`) | Bundled binary | SIL OFL 1.1, confirmed from embedded name-table license. Copyright 2021 The Hanken Grotesk Project Authors (https://github.com/marcologous/hanken-grotesk). Font metadata credits Hanken Design Co. and Alfredo Marco Pradil. Full text: [Hanken-Grotesk-OFL.txt](licenses/Hanken-Grotesk-OFL.txt). |
| Jost (`app/src/main/res/font/jost.ttf`) | Bundled variable font | SIL OFL 1.1, confirmed from embedded name table. Copyright 2020 The Jost Project Authors (https://github.com/indestructible-type/Jost). Unmodified binary reused from the landing page. Full text: [Jost-OFL.txt](licenses/Jost-OFL.txt). |
| IBM Plex Mono Medium (`app/src/main/res/font/ibm_plex_mono_medium.ttf`) | Bundled binary | SIL OFL 1.1, confirmed from embedded name table. Copyright 2017 IBM Corp.; reserved name Plex in the accompanying license. Unmodified binary reused from the landing page. Full text: [IBM-Plex-Mono-OFL.txt](licenses/IBM-Plex-Mono-OFL.txt). |
| Gradle wrapper JAR and scripts | See wrapper properties | Apache-2.0; scripts retain upstream notices. The checked-in JAR matches the wrapper embedded in the locally installed Gradle 9.9.0 distribution, SHA-256 `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`. Independent upstream checksum verification remains open. |

Offline Gradle resolution of releaseRuntimeClasspath selected AndroidX graphics-path 1.0.1, MapLibre Android SDK 13.6.1 and Shizuku api/provider/aidl/shared 13.1.5. The unsigned release APK contains native libraries named `libandroidx.graphics.path.so` and `libmaplibre.so`. Its named notice files include AndroidX licenses and the OkHttp public-suffix NOTICE. The cached MapLibre Android SDK 13.6.1 POM declares BSD. Its attribution UI resources do not establish notice coverage for its native dependencies. A complete release notice bundle and review of map/provider terms remain open.

**Notice-bundle check — 21 Nov (offline, cached artifacts).** The cached MapLibre `android-sdk` 13.6.1 and `maplibre-android-gestures` 0.0.4 AARs contain no license or notice files (only `R.txt`, `public.txt`, `proguard.txt`); POMs say BSD (SDK, gestures) and Apache-2.0 (geojson, turf 6.0.1). The release APK ships `libmaplibre.so` for four ABIs, which statically bundles native dependencies that no shipped file credits. Its bundled notice files are only six AndroidX `LICENSE.txt` copies and the OkHttp public-suffix NOTICE. **Gap before APK distribution:** add MapLibre Native's BSD-2-Clause text and its native-dependency notices (take them from the matching upstream 13.6.1 tag), plus Shizuku's MIT notice. Source-only publication is not blocked by this; an APK release is.

## Build and test dependencies

| Component | Declared version | Initial license label |
| --- | --- | --- |
| Android Gradle plugin | 9.5.1 | Apache-2.0 |
| Kotlin JVM/Compose/serialization plugins | 2.4.21 | Apache-2.0 |
| KSP | 2.3.13 | Apache-2.0 |
| Room compiler | 2.8.5 | Apache-2.0 |
| JUnit 4 | 4.13.2 | EPL-1.0 |
| JUnit Jupiter/platform | BOM 6.1.3 | EPL-2.0 |
| Kotest assertions | 6.2.6 | Apache-2.0 |
| Robolectric | 4.17 | MIT |
| AndroidX Test JUnit/runner/core | 1.3.0 / 1.7.0 / 1.7.0 | Apache-2.0 |
| Espresso core | 3.7.0 | Apache-2.0 |
| Compose UI test / manifest | Compose BOM | Apache-2.0 |
| kotlinx.coroutines test | 1.11.0 | Apache-2.0 |

## Remote content and implementation provenance

- OpenFreeMap styles/tiles and their underlying map data are remote services/content. MapLibre attribution is enabled in `PlaceMap.kt`. Verify current data/style attribution and usage terms before distribution.
- Google Places/Maps grounding, Vertex/Gemini and Exa are external services with their own terms. Places author attribution is shown with photos; code-level attribution is not proof that all display/caching requirements are satisfied. The existing non-Google-map display concern remains open in the audit.
- Documentation/mockup HTML loads Google Fonts (Hanken Grotesk and JetBrains Mono) and Unsplash image URLs. Those assets are not relicensed under Apache-2.0; review photo provenance and attribution before publishing the rendered pages. No personal photo/video binary is tracked in this subtree.
- Routine scheduling (`core/.../routine/`, and the files that say so in their header) is adapted from OpenPhoneUse (https://github.com/joungminsung/OpenPhoneUse), Copyright (c) 2026 joungminsung, MIT License: full text in `licenses/OpenPhoneUse-MIT.txt`. Adapted files keep that notice in their header.
- `hidden/ShellService.kt` references scrcpy's virtual-display approach and flags. Inspect implementation provenance for copied/adapted code and preserve any upstream Apache notices required. This pass did not invent an authorship notice or certify originality.
