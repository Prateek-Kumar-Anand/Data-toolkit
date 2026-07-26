# Data Toolkit — Android

A native Android app (Kotlin) covering: Data Cleaning, OCR, PDF Operations,
Excel/CSV, Web Scraping, Email Extraction, Batch Processing, Smart Caching,
Auto-Retry Logic, Quality Scoring, and an Analytics Dashboard.

## How to build the actual APK

This is Kotlin source + Gradle config, not a compiled binary — you build the
`.apk` yourself:

1. Install **Android Studio** (free): https://developer.android.com/studio
2. Open Android Studio → **Open** → select the `DataToolkit` folder (the one
   containing this README and `settings.gradle`).
3. Let it **Sync** (bottom-right progress bar). First sync downloads Gradle
   itself plus all libraries (ML Kit, PdfBox-Android, jsoup, fastexcel,
   MPAndroidChart, Room, WorkManager) — needs internet, takes a few minutes.
4. Plug in your phone (with USB debugging on) or start an emulator, then
   press the green ▶ **Run** button — this installs a debug build directly.
5. For a shareable `.apk` file: **Build → Build Bundle(s) / APK(s) → Build
   APK(s)**. Studio will show a notification with a "locate" link to the
   file, normally under `app/build/outputs/apk/debug/app-debug.apk`.

If Gradle Sync complains about a missing `gradlew` wrapper, click the "Try
Again" / "Setup Gradle wrapper" prompt Studio shows — this is normal since
the wrapper jar itself isn't included in this source drop, only its version
config (`gradle/wrapper/gradle-wrapper.properties`).

## Minimum requirements
- Android Studio Koala (2024.1) or newer
- JDK 17 (bundled with recent Android Studio)
- A device/emulator running Android 7.0 (API 24) or newer

## Feature → implementation map

| Feature | How it's built |
|---|---|
| Data Cleaning & Processing | `features/datacleaning/DataCleaner.kt` — trim, collapse whitespace, case normalize, de-dupe rows, drop empty rows, fill missing values. Works on pasted CSV text or rows loaded from Excel. |
| OCR | `features/ocr/OcrHelper.kt` — on-device Google ML Kit text recognition (no API key, no network needed). Camera or gallery input. |
| PDF Operations | `features/pdf/PdfHelper.kt` — PdfBox-Android: merge, split by page range, extract text (whole doc or per page), rotate, build a PDF from a set of images. |
| Excel/CSV | `features/excel/ExcelCsvHelper.kt` — fastexcel/fastexcel-reader (pure Java, Android-friendly) for `.xlsx`; built-in CSV parser/writer. Convert either direction. |
| Web Scraping | `features/scraping/Scraper.kt` — jsoup fetch + parse (text, links, HTML tables), wrapped in `RetryPolicy`. |
| Email Extraction | `features/email/EmailExtractor.kt` — regex extraction + validation + de-dupe, from pasted text or a scraped URL. |
| Batch Processing | `features/batch/BatchWorker.kt` — a `CoroutineWorker` (WorkManager) that runs a queued set of images (OCR) or PDFs (text extraction) in the background, with progress reporting. |
| Smart Caching | `core/cache/CacheManager.kt` — every feature hashes its input (SHA-256) and checks the Room-backed cache before redoing work. |
| Auto-Retry Logic | `core/network/RetryPolicy.kt` — generic exponential-backoff retry wrapper, used by scraping and the batch worker; WorkManager's own `BackoffCriteria` retries a whole batch run if too many items fail. |
| Quality Scoring | `core/quality/QualityScorer.kt` — a 0-100 rubric for text, tables, and email lists, shown after every operation. |
| Analytics Dashboard | `ui/DashboardActivity.kt` — reads the shared `ProcessedItem` history table and renders totals, success rate, average quality, and a per-feature bar chart (MPAndroidChart). |

## Notes / things worth knowing before you ship this
- **Web Scraping** only reads publicly accessible pages the same way a
  browser would (via jsoup) — it respects nothing beyond what's already
  public in the HTML; it won't get past logins or JS-rendered content.
- **Storage**: saved files go to the app's external files dir
  (`Android/data/com.prateek.datatoolkit/files/Documents`) so no extra
  storage permission dance is needed on modern Android versions.
- **Icons** are placeholder "DT" monograms generated for this drop — swap
  `res/mipmap-*/ic_launcher*.png` for your own branding whenever you like.
- Nothing here calls any paid API — OCR, PDF, Excel, and scraping are all
  on-device/local, so there are no per-request costs.
