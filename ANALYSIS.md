# Tracket — কোডবেস অ্যানালাইসিস

> বিশ্লেষণের তারিখ: ২০২৬-১০-০৮ · ব্রাঞ্চ: `arena/71cd0be0-tracket` · বেস কমিট: `9af65ab` (“v0.1: import button, ripple delete, single clock”)
>
> **যাচাইয়ের সীমাবদ্ধতা:** এই স্যান্ডবক্সে JDK / Android SDK / Gradle নেই, এবং রিপোতে `gradlew` নেই — তাই কম্পাইল বা টেস্ট রান করে দেখা সম্ভব হয়নি। নিচের সবকিছু **স্ট্যাটিক কোড রিভিউ** (প্লাস Media3 upstream সোর্স দিয়ে API যাচাই) থেকে এসেছে। Media3-এর যে API গুলো ব্যবহার করা হয়েছে (`Presentation`, `EditedMediaItemSequence`, `Composition`, `experimentalSetForceAudioTrack`) সেগুলো `androidx/media` রিলিজ ব্রাঞ্চে আছে — ডিপ্রিকেটেড হলেও কম্পাইল হবে।

---

## ১. এক প্যারায় ভার্ডিক্ট

একটি **খুব ভালো স্ট্রাকচার্ড প্রোটোটাইপ** যার মেরুদণ্ড (immutable state + StateFlow + pure lane layout + ExoPlayer-কেন্দ্রিক একক ক্লক) অসাধারণ পরিষ্কার — কিন্তু **UI-এর আকার ইঞ্জিনের আকারের চেয়ে অনেক বড়**। বাস্তবে যা রেন্ডার/এক্সপোর্ট হয় তার চেয়ে ঢের বেশি কিছু “আছে” বলে দেখানো হচ্ছে। সবচেয়ে বড় তিনটি সমস্যা: **(ক) প্রিভিউ আর এক্সপোর্ট আলাদা জিনিস** (WYSIWYG ভাঙা), **(খ) ~৩০% UI কোড ডেড** (কোনও বাটন থেকে পৌঁছানো যায় না), **(গ) কিছু জায়গায় UI মিথ্যে কথা বলে** (স্প্লিট ও “Project autosaved” স্ন্যাকবার)।

### স্কোরকার্ড

| দিক | গ্রেড | মন্তব্য |
|---|---|---|
| আর্কিটেকচার / স্টেট মডেল | **A−** | ইমিউটেবল স্টেট, একমুখী ডেটা ফ্লো, একটিই ক্লক — দারুণ |
| ডোমেইন লজিক (`LaneLayout`, মডেল) | **A** | পিওর ফাংশন, ভালো কমেন্ট, কোনও Android ডিপেন্ডেন্সি নেই |
| UI স্ট্রাকচার | **C** | ১,০০০+ লাইনের গড-কম্পোজেবল, ৬৯৮ লাইনের স্ক্রিন, অনেক ডেড শিট |
| ফিচার সততা (UI বনাম বাস্তবতা) | **D+** | প্রিভিউ ≠ এক্সপোর্ট; “autosaved” মিথ্যে; স্প্লিট নীরবে ব্যর্থ |
| পারফরম্যান্স | **C+** | ৩০ Hz-এ পুরো গাছ রিকম্পোজ; ৫.৮ MB ডেমো JPEG |
| টেস্টিং | **B−** | ২০টি পিউর JVM টেস্ট দারুণ, কিন্তু UI টেস্ট শূন্য |
| বিল্ড / CI / ডিপেন্ডেন্সি | **C** | `gradlew` নেই, ~২০ অব্যবহৃত ডিপেন্ডেন্সি, ডেড Gemini স্ক্যাফোল্ডিং |
| রিলিজ-রেডিনেস | **D** | R8 বন্ধ, `com.example` namespace, প্রজেক্ট সেভ নেই |

---

## ২. প্রজেক্ট স্ন্যাপশট

| | |
|---|---|
| ধরন | Android অ্যাপ (Compose, single-activity) |
| ভাষা / টুলচেইন | Kotlin 2.2.10, AGP 9.1.1, Java 11, Gradle 9.3.1 (CI-তে) |
| SDK | minSdk 24, targetSdk 36, compileSdk 36.1 |
| মিডিয়া স্ট্যাক | Media3 1.11.1 (ExoPlayer + Transformer + Effect) |
| মোট Kotlin | **৯,৬০৩ লাইন** (৩৯ ফাইল), গড় ২৪৬ লাইন/ফাইল |
| বৃহত্তম ফাইল | `TransformView.kt` ১,০৮৪ · `EditShapeView.kt` ৮৪৫ · `AppliedEffectsView.kt` ৮২৮ · `CropView.kt` ৭২৫ · `EditorSheets.kt` ৭১১ · `VideoEditorScreen.kt` ৬৯৮ · `TimelineLayerPanel.kt` ৭৩৩ |
| টেস্ট | ২০ unit (`VideoEditorViewModelTest`), ১ Robolectric, ১ instrumentation (টেমপ্লেট) |
| ইতিহাস | ১টি কমিট — সবকিছু এক ঝাঁকড়ায় তৈরি |

---

## ৩. আর্কিটেকচার ম্যাপ

```
MainActivity
   └── VideoEditorScreen  (ui/, 698 লাইন — সবকিছুর হাব)
         ├── engine/EditorPlayer     ExoPlayer, মেইন ট্র্যাক ব্যাক-টু-ব্যাক চালায়, একমাত্র ক্লক
         ├── engine/VideoExporter    Media3 Transformer → H.264/AAC MP4 → Movies/Tracket
         ├── engine/MediaImporter    Photo Picker → name/mime/duration
         ├── viewmodel/VideoEditorViewModel (616 লাইন) — সব স্টেটের একমাত্র মালিক
         │        StateFlow: playhead, totalDuration, isPlaying, tracks,
         │                   selectedClipId, aspect, resolution, undo/redo, export…
         └── ui/components/*       ২২টি কম্পোজেবল (ক্যানভাস, টাইমলাইন, শিট, ডায়লগ)

model/  TimelineClip (৪৫+ প্রপার্টির God-object), TimelineTrack, LaneLayout, EditorModels
```

**ডেটা ফ্লো:** `ViewModel (StateFlow)` → `Screen (collectAsState)` → কম্পোজেবল → কলব্যাক → `ViewModel`। স্টেট ইমিউটেবল (`data class` + `copy`)। ক্লক হিসেবে ExoPlayer-ই একমাত্র সত্য — ভিউমডেলের ওয়াল-ক্লক টাইমার শুধু “কোনও মিডিয়া নেই” অবস্থার ফলব্যাক (`VideoEditorViewModel.kt:94`)। এটি একটি **সচেতন, সঠিক সিদ্ধান্ত** — আগের ডুয়াল-ক্লক জামেলা এড়ানো হয়েছে।

**`LaneLayout` (model/)** — পুরো প্রজেক্টের সবচেয়ে পরিষ্কার অংশ: পিওর ফাংশন, কোনও Android নির্ভরতা নেই, নিয়মগুলো স্পষ্ট (লেনে কখনও ওভারল্যাপ হবে না, টাচিং-এন্ড ওভারল্যাপ নয়, খালি লেন মুছে যায়, লেনের সংখ্যার কোনও সীমা নেই)।

---

## ৪. ফিচার রিয়েলিটি ম্যাট্রিক্স

### 🟢 সত্যিই কাজ করে (এন্ড-টু-এন্ড)

| ফিচার | কোথায় |
|---|---|
| ভিডিও/ফটো ইমপোর্ট (Photo Picker, ≤২০ আইটেম) | `MediaImporter.kt`, `VideoEditorScreen.kt:148` |
| ExoPlayer প্রিভিউ, ব্যাক-টু-ব্যাক প্লেব্যাক | `EditorPlayer.kt` |
| প্লেহেড স্ক্রাব + ফ্লিং (ভেলোসিটি-ভিত্তিক) | `TimelineLayerPanel.kt:134-188` |
| স্প্লিট (ট্রিম অফসেট সহ) | `VideoEditorViewModel.kt:201-234` |
| ডিলিট + ripple (মিডিয়া গ্যাপ বন্ধ) | `VM:236-261` |
| ডুপ্লিকেট (মিডিয়া ইনসার্ট + শিফট) | `VM:263-286` |
| Shape লেয়ার যোগ + ট্রান্সফর্ম (প্রিভিউতে) | `VM:369-448`, `CanvasArea.kt` |
| আসপেক্ট রেশিও (প্রিভিউ + এক্সপোর্টে) | `VideoExporter.kt:60,123` |
| MP4 (H.264/AAC) এক্সপোর্ট + প্রগ্রেস | `VideoExporter.kt` |
| Undo/Redo (ট্র্যাক স্ন্যাপশট) | `VM:172-199` |
| Multi-lane টাইমলাইন (`LaneLayout`) | `model/LaneLayout.kt` |

### 🟡 UI আছে, কিন্তু কাজ করে না / আংশিক

- **ট্রান্সফর্ম** (position/scale/rotation/3D/warp): শুধু **প্রিভিউতে** (`CanvasArea.kt:124-143`) — এক্সপোর্টে একেবারেই নেই।
- **Crop**: `updateClipCrop()` স্টেটে সেভ করে, কিন্তু **প্রিভিউ ও এক্সপোর্ট কোথাও পড়েই না**; উপরন্তু CropView UI-তে পৌঁছানোর রাস্তাই নেই।
- **Flip**: শুধু **এক্সপোর্টে** (`VideoExporter.kt:118-122`) — প্রিভিউতে `isFlipped*` কোথাও পড়া হয়নি।
- **Export সেটিংস** (resolution/FPS/quality/format): ডায়ালগে স্ট্যাটিক টেক্সট, কোনও হ্যান্ডলার নেই → সবসময় 1080p/30।
- **Master mute**: প্রিভিউতে (`editorPlayer.setMuted`) — এক্সপোর্টে নেই।
- **Trim**: ড্র্যাগ-হ্যান্ডেল নেই; শুধু split + delete দিয়ে সম্ভব।
- **ছবির ডিউরেশন**: সবসময় ৫,০০০ ms (`MediaImporter.kt:13`), বদলানোর কোনও UI নেই।

### 🔴 “Coming soon” / অচল

Text, Audio, Overlay, Sticker লেয়ার; Effects; Animations; Blend; Replace; Speed; per-clip Volume; Keyframes; প্রজেক্ট সেভ/লোড। (`comingSoon` ১২ বার ব্যবহৃত — `VideoEditorScreen.kt:101`)

### 💀 ডেড কোড — কোনও বাটন থেকে পৌঁছানো যায় না

`showAddAudioSheet`, `showAddTextSheet`, `showClipEffectsSheet`, `showClipAnimationsSheet`, `showClipReplaceSheet`, `showClipBlendSheet`, `showClipCropSheet`, `showAppliedEffectsMode`, `showEditShapeMode` — **এই ৯টি ফ্ল্যাগ কোথাও `true` হয় না**। ফলে নিচের ফাইলগুলো সম্পূর্ণরূপে অপ্রাপ্য:

| ফাইল | লাইন |
|---|---|
| `EditShapeView.kt` | ৮৪৫ |
| `AppliedEffectsView.kt` | ৮২৮ |
| `CropView.kt` | ৭২৫ |
| `ClipToolsSheets.kt` (৫টি শিট) | ৩২৮ |
| `EditorSheets.kt`-এর AddAudio/AddText অংশ | ~১২০ |
| **মোট ≈ ২,৮৫০ লাইন** | **কোডবেসের ~৩০%** |

---

## ৫. ক্রিটিক্যাল সমস্যা (P0)

### P0-1 · প্রিভিউ ≠ এক্সপোর্ট (WYSIWYG ভাঙা) — সবচেয়ে বড় বিপদ
- `CanvasArea.kt:124-143` প্রিভিউতে `transformX/Y`, `scalePercent`, `rotation{Z,X,Y}`, `pivot` প্রয়োগ করে।
- `VideoExporter.kt:106-130`-এর `buildItem()` এই প্রপার্টিগুলোর **একটিও পড়ে না** — শুধু flip + Presentation।
- `cropWidthPercent`…`cropPreset` স্টেটে আছে, **কোথাও ব্যবহার নেই**।
- এক্সপোর্ট শুধু `LayerType.VIDEO` (`VideoExporter.kt:54-55`) → text/shape/overlay/sticker/audio কখনও ভিডিওতে আসে না।
- Flip উল্টোদিকে: এক্সপোর্টে আছে, প্রিভিউতে নেই।

> ব্যবহারকারী ক্যানভাসে ক্লিপ ঘোরাবে/সরাবে/ক্রপ করবে, “Export” চাপবে → সম্পূর্ণ অন্য ভিডিও।

**সমাধান:** অন্তত (ক) `buildItem()`-এ `ScaleAndRotateTransformation` + `Crop`/`Presentation.createForWidthAndHeight(w,h,LAYOUT_SCALE_TO_FIT_WITH_CROP)` দিয়ে transform/crop প্রয়োগ, (খ) প্রিভিউতে flip যোগ, (গ) VIDEO বাদে অন্য লেয়ার এক্সপোর্ট না হলে বাটনটাই ডিজেবল + স্পষ্ট বার্তা।

### P0-2 · Export ডায়ালগের অপশনগুলো সম্পূর্ণ নকল
`_exportSettings` প্রাইভেট, কোথাও মিউটেট হয় না (`VM:63-64`)। ডায়ালগে হার্ডকোডড `"1080p FHD (1920x1080)"` (`EditorSheets.kt`, ExportModalDialog অঞ্চল) — ৯:১৬ সিলেক্ট করলেও টেক্সট 1920x1080-ই দেখায়। `VideoExporter.kt:128` FPS হার্ডকোডড ৩০। 

**সমাধান:** হয় `setExportSettings()` যোগ করে রেজোলিউশন/রেট সত্যিই প্রয়োগ, নয়তো অপশন-রো গুলো সরিয়ে এক লাইনে “1080p · 30fps · H.264” দেখান।

### P0-3 · “Project autosaved” — মিথ্যে
`VideoEditorScreen.kt:218-220`: ব্যাক বাটনে স্ন্যাকবার “Project autosaved”। কিন্তু কোডবেসে **কোনও পার্সিস্টেন্স নেই** (Room/Datastore/File — কিছুই নেই, শুধু version-catalog-এ ঘোষিত)। অ্যাপ বন্ধ করলে সব হারিয়ে যায়।

**সমাধান:** অন্তত `TimelineClip`/`TimelineTrack` কে `@Serializable` করে JSON-এ অটোসেভ (বা সততার সাথে “Draft not saved yet” দেখান)।

### P0-4 · স্প্লিট নীরবে ব্যর্থ হলেও “সফল” বার্তা
`VM:201-234`: প্লেহেড clip-এর শেষ/শুরু থেকে ৫০০ ms-এর মধ্যে থাকলে কিছুই হয় না। কিন্তু স্ক্রিনে `snackbarHostState.showSnackbar("Clip split at playhead")` (`VideoEditorScreen.kt:282` অঞ্চল) ** unconditional**।

**সমাধান:** `splitSelectedClip()` থেকে `Boolean` রিটার্ন করান; ব্যর্থ হলে “Playhead must be inside the clip” দেখান (বা স্প্লিট বাটন ডিজেবল)।

### P0-5 · Undo/Redo অসঙ্গতিপূর্ণ ও আনবাউন্ডেড
- `updateClipTransform()` (`VM:450`) ও `updateClipFullTransform()` (`VM:474`) → **pushHistory নেই** → ট্রান্সফর্ম একদমই আনডু করা যায় না।
- `updateClipCrop()` (`VM:517`) → প্রতি ফ্রেমে pushHistory → ড্র্যাগ করলে স্ট্যাকে শত শত প্রায়-একই স্ন্যাপশট জমে, মেমরি আনবাউন্ডেড।
- কোনও সীমা নেই (ক্যাপড ডেক নেই), কোনও coalescing নেই।

**সমাধান:** (ক) জেসচার শেষে (`onApplyTransform`) একবার history পুশ, (খ) `ArrayDeque` + ৫০-এর ক্যাপ, (গ) crop-এর মতো কন্টিনিউয়াস আপডেটকে ডিবাউন্স।

### P0-6 · ~৩০% ডেড UI (উপরে দেখুন)
`CropView`, `EditShapeView`, `AppliedEffectsView`, `ClipToolsSheets` — মোট ~২,৮৫০ লাইন কোনও রুট থেকে পৌঁছানো যায় না। এগুলো রাখলে বিল্ড সময়, রিভিউ বোঝা এবং R8-এর পর APK সবই বাড়ে।

**সমাধান:** হয় বাটন ওয়্যার আপ করুন (crop/effects আসলেই চালু করুন), নয়তো ব্রাঞ্চ থেকে মুছুন (গিট ইতিহাসে থাকবে)।

---

## ৬. মিডিয়াম লেভেলের সমস্যা (P1)

1. **ক্লিপ ড্র্যাগ/ট্রিম নেই।** `TimelineClipItem` (`TimelineLayerPanel.kt:421`) শুধু tap-to-select। টাইমলাইনে ক্লিপ সরানো বা এজ-ট্রিম করার কোনও উপায় নেই — README দাবি করে “trim/resize”। ফলে “ট্রিম” মানে বাস্তবে split + delete-ripple।
2. **ছবির ডিউরেশন হার্ডকোডড ৫ সেকেন্ড** (`MediaImporter.kt:13`)।
3. **প্লেব্যাক ও এডিট একসাথে করলে জিটার।** `mediaClips` প্রতিবার `tracks` বদলালে নতুন লিস্ট (`VideoEditorScreen.kt:118`) → `LaunchedEffect(isPlaying, mediaClips)` (`:132`) রিস্টার্ট হয় → প্রতি এডিটে `editorPlayer.seekTo(..., force = true)` পুনরায় চলে (সামান্য পেছনে ঝাঁকুনি)।
4. **রিকম্পোজিশন ব্যয়।** প্লেহেড `VideoEditorScreen`-এর রুটে `collectAsState` (`:82`) → ৩০ Hz-এ ক্যানভাস, প্লে-কন্ট্রোল, টাইমস্কেল, টাইমলাইন সব রিকম্পোজ। কমপক্ষে `currentPlayheadMs` কে যত নিচে যত সম্ভব নামান (টাইমলাইন/রুলারে সীমাবদ্ধ) ও `derivedStateOf` ব্যবহার করুন।
5. **ডেমো কোড প্রোডাকশন পাথে।** `TimelineLayerPanel.kt` ~৪৫৮: `clip.name.contains("Yellow") || clip.name.contains("Vlog")` — ক্লিপের নাম দেখে কোন placeholder ছবি দেখাবেন তা ঠিক করে।
6. **রিসোর্স বloat.** `res/drawable/`-এ ৭টি JPEG = **~৫.৮ MB**, density-qualified নয়, WebP নয়, এবং এগুলো শুধু ডেমো placeholder/লোগোর জন্য। শুধু `drawable-nodpi` + WebP-তেই কয়েক MB বাঁচবে (`isCrunchPngs = false` রাখা আছে)।
7. **R8 বন্ধ** (`isMinifyEnabled = false`) — রিলিজ APK অযথা বড়।
8. **লং এক্সপোর্টে কোনও ফোরগ্রাউন্ড সার্ভিস/নোটিফিকেশন নেই** → ব্যাকগ্রাউন্ডে গেলে প্রসেস মারা পড়তে পারে; ক্যানসেল বাটনও নেই।
9. **API ২৪–২৮-এ `Uri.fromFile` রিটার্ন** (`VideoExporter.kt:184`) → অন্য অ্যাপে পাঠালে `FileUriExposedException`। `FileProvider` ব্যবহার করুন (`res/xml/file_paths.xml` আগেই আছে, কিন্তু ব্যবহার নেই!)।
10. **সব স্ট্রিং হার্ডকোডড।** `strings.xml`-এ শুধু `app_name` → কোনও i18n সম্ভব নেই।
11. **অ্যাক্সেসিবিলিটি অসম্পূর্ণ।** বেশিরভাগ আইকন-বাটনে `contentDescription = null`; প্রধান ক্যানভাস/টাইমলাইনে কোনও ডেস্ক্রিপশন নেই (মাত্র ৪৪টি `testTag` আছে, কিন্তু সেগুলো ব্যবহার করে কোনও টেস্ট নেই)।
12. **`namespace = "com.example"`** (`app/build.gradle.kts:9`) vs `applicationId = "com.aistudio.videoeditor.tracket"`। রিলিজের আগে প্যাকেজ/ডিরেক্টরি রিনেম করতে হবে — পরে করলে কষ্ট হবে।
13. **রিডুন্ডেন্ট স্টেট।** `TimelineClip`-এ একই জিনিসের দুই নাম: `transformX/Y` + `shapeOffsetX/Y`, `scalePercent` + `shapeScaleX/Y` — `updateClipFullTransform()` (`VM:486-503`) দুটোই সিঙ্ক করে রাখতে হয়, ভুল হওয়ার জায়গা।

---

## ৭. বিল্ড, CI ও ডিপেন্ডেন্সি

### CI (`.github/workflows/build.yml`) — মোটামুটি ঠিক, কিন্তু
- ✅ APK আগে বিল্ড, টেস্ট পরে — ভালো। ✅ টেস্ট রিপোর্ট আর্টিফ্যাক্ট (failure-তে)।
- ⚠️ **`gradlew` নেই** (শুধু `gradle/wrapper/gradle-wrapper.properties`) → CI সিস্টেম-গ্র্যাডল ৯.৩.১ ব্যবহার করে, আর README-এর `./gradlew testDebugUnitTest` কাজ করে না। **রিপোতে `gradlew` + `gradlew.bat` যোগ করুন** — এটিই সবচেয়ে সস্তা CI-নির্ভরতা কমানোর উপায়।
- ⚠️ `main`-এ পুশ ছাড়া রান হয় না → এই ব্রাঞ্চে কাজ করলে CI চলবে না। `pull_request` ট্রিগার যোগ করুন।
- ⚠️ কোনও `ktlint`/`detekt`/`gradle lint` স্টেপ নেই।

### ডিপেন্ডেন্সি হাইজিন (`gradle/libs.versions.toml`)
`app/build.gradle.kts` সত্যিই যা ব্যবহার করে: Compose BOM, Material3, activity/lifecycle, Media3 (common/exoplayer/transformer/effect/ui), Coil, coroutines, JUnit/Robolectric।
**ঘোষিত কিন্তু অব্যবহৃত (~২০ এন্ট্রি):** Room, Retrofit, Moshi, OkHttp + logging-interceptor, Navigation, DataStore, CameraX (৪টি), Play Services Location, Accompanist Permissions, Credentials + Google ID, **Firebase BOM/AI/Firestore/Auth/AppCheck**, KSP, google-services/secrets প্লাগিন।
→ বিল্ড সময় ও সাপ্লাই-চেইন সারফেস কমাবে; প্রয়োজনের সময় যোগ করা যাবে।

### ডেড AI স্ক্যাফোল্ডিং
- `metadata.json`: `"majorCapabilities": ["MAJOR_CAPABILITY_SERVER_SIDE_GEMINI_API"]`
- `.env.example`: `GEMINI_API_KEY` নির্দেশনা

কিন্তু **কোডবেসে কোথাও Gemini/Firebase/নেটওয়ার্ক কল নেই** (ইন্টারনেট পারমিশনও নেই)। এগুলো মুছে ফেলুন বা সত্যিই কাজে লাগান।

### ম্যানিফেস্ট / সিকিউরিটি
- 👍 Photo Picker ব্যবহার → কোনও স্টোরেজ পারমিশন দরকার নেই, ম্যানিফেস্টে কোনও `<uses-permission>` নেই (দারুণ)।
- 👍 `dataExtractionRules` + `backup_rules` ঠিকঠাক। ⚠️ `android:allowBackup="true"` — প্রজেক্ট ডেটা সেভ না থাকলেও ব্যাকআপ চালু (আপাতত নিরীহ)।
- ⚠️ কোনও `android:screenOrientation`/`configChanges` নেই → রোটেশনে Activity রিক্রিয়েট হবে (প্লেয়ার রিলিজ/রিক্রিয়েট)।

### ভার্সন ড্রিফট
`composeBom = "2024.09.00"` (২০২৪-এর) Kotlin 2.2.10-এর সাথে; `activity-compose 1.10.1`, `lifecycle 2.8.7`, `navigation 2.8.9` — সব ~২ বছর পুরনো। Compose BOM-কে অন্তত ২০২৫-x-এ আপগ্রেড করুন (Compose 1.7+/1.9+ পারফরম্যান্স উন্নতি পাবেন)।

---

## ৮. টেস্ট কভারেজ

| | অবস্থা |
|---|---|
| `VideoEditorViewModelTest` (২০ টেস্ট) | 🟢 চমৎকার — pure JVM, কোনও Robolectric লাগে না। কভার করে: লেন প্লেসমেন্ট, নো-ওভারল্যাপ ইনভ্যারিয়েন্ট, মিডিয়া-নট-স্পেশাল, split-এর ট্রিম অফসেট, ripple delete, duplicate শিফট, undo/redo, seek ক্ল্যাম্পিং, ২৫-লেন লিমিট নেই |
| `ExampleRobolectricTest` | 🟡 শুধু `app_name` স্ট্রিং পড়ে — টেমপ্লেট |
| `ExampleInstrumentedTest` | 🟡 টেমপ্লেট, কখনও চলে না (CI-তে emulator নেই) |
| UI/Compose টেস্ট | 🔴 **০** — অথচ ৪৪টি `testTag` বসানো আছে |

**ফাঁক:** কোনও টেস্ট নেই — `LaneLayout` (সরাসরি), `VideoExporter.outputSize()` (রেজোলিউশন ম্যাথ — রিগ্রেশন-প্রবণ), `EditorPlayer` (timeline↔playlist ম্যাপিং), ভিউমডেলের ট্রান্সফর্ম/কার্প আপডেট, undo-stack সীমা।

---

## ৯. README রোডম্যাপ বনাম বাস্তবতা

README-এর **Phase 1** (Kotlin editor) লক্ষ্যমাত্রা বনাম বর্তমান অবস্থা:

| Phase 1 লক্ষ্য | বাস্তবতা |
|---|---|
| Layer reorder, timeline drag, trim/resize | ❌ ড্র্যাগ/ট্রিম নেই; শুধু split + ripple-delete |
| Lock / visibility / duplicate / delete | 🟡 duplicate/delete আছে; lock/visibility UI নেই |
| Accurate frame/ms conversion, zoom, snapping | 🟡 স্ক্রাব/ফ্লিং আছে; **জুম নেই** (`TIMELINE_PX_PER_MS` ফিক্সড), স্ন্যাপিং নেই |
| Property system: position/scale/rotation/opacity/anchor/**keyframes** | 🟡 প্রিভিউ-তে কিছু; **opacity নেই; keyframes নেই** |
| Animation (linear/easing/Bézier/motion path/physics) | ❌ শুধু `animationName` স্ট্রিং; UI “coming soon” |
| Compositing: image/video/text/shape/group/null/controller | 🟡 image/video/shape প্রিভিউ; বাকি কিছু না |
| Effects (blur/glow/color/shadow/transform) | ❌ UI অচল |
| Export: MP4 + resolution/FPS/audio | 🟡 MP4 + aspect ✅; resolution/FPS ডেড; audio শুধু মূল ট্র্যাক |

→ **Phase 1-এর মূল কাজের আনুমানিক ৩০–৩৫% সম্পন্ন**, যদিও UI-এর পরিমাণ দেখে মনে হয় ৮০%। “Phase 2 (Kotlin freeze) / Phase 3 (C++ migration)” কথা বলার আগে Phase 1-এর মিউজ-রিলেটেড ফাঁক (drag/trim/zoom/snap/undo/export-parity) ভরাট করা জরুরি — নইলে C++ মাইগ্রেশন ভুল API-র উপর দাঁড় করার ঝুঁকি থাকবে।

**ভালো খবর:** README যে স্থাপত্য সীমানা চায় (`UI → EditorEngine API → Timeline/Layer/Render Engine`), তার বীজ আগেই আছে — `engine/` প্যাকেজে `EditorPlayer`/`VideoExporter`/`MediaImporter` আলাদা, আর `model/LaneLayout` একেবারে Android-মুক্ত। এই সীমানা এখনই আনুষ্ঠানিক (interface) করলে Phase 3 সহজ হবে।

---

## ১০. প্রায়োরিটাইজড অ্যাকশন প্ল্যান

### P0 — ব্যবহারকারীর বিশ্বাস বাঁচান (প্রথমেই করুন)
1. **প্রিভিউ/এক্সপোর্ট প্যারিটি**: `VideoExporter.buildItem()`-এ transform + crop প্রয়োগ; প্রিভিউতে flip যোগ। না পারলে সংশ্লিষ্ট UI বন্ধ করুন।
2. **মিথ্যে দাবি বন্ধ**: “Project autosaved” → সত্যি অটোসেভ (JSON) বা সৎ বার্তা; স্প্লিটের ফলাফল অনুযায়ী স্ন্যাকবার।
3. **Export ডায়ালগ**: হয় অপশন সত্যি করুন, নয়তো সরিয়ে ফেলুন।
4. **Undo**: ট্রান্সফর্ম স্টেপে history; ক্যাপড `ArrayDeque` (৫০)।

### P1 — ভিত্তি মজবুত
5. `gradlew` + `gradlew.bat` যোগ; CI-তে `pull_request` ট্রিগার; ktlint যোগ।
6. **টাইমলাইন ড্র্যাগ + ট্রিম হ্যান্ডেল** (`TimelineClipItem`), অন্তত মিডিয়া ক্লিপের জন্য।
7. প্লেহেড রিকম্পোজিশন স্কোপ ছোট করুন (টাইমলাইনে সীমাবদ্ধ)।
8. ডেড UI (~২,৮৫০ লাইন) সরান বা ওয়্যার আপ করুন; ডেমো `name.contains(...)` ব্রাঞ্চিং মুছুন।
9. অব্যবহৃত ডিপেন্ডেন্সি + Gemini/Firebase স্ক্যাফোল্ডিং মুছুন; Compose BOM আপগ্রেড করুন।
10. `namespace`/প্যাকেজ → `com.aistudio.videoeditor.tracket`; স্ট্রিংগুলো `strings.xml`-এ।

### P2 — স্কেল করার আগে
11. `TimelineClip` ভাঙুন (`Transform`, `Crop`, `ShapeStyle`, `Media` সাব-স্ট্রাকচার) — God-object।
12. `VideoEditorScreen` (৬৯৮) → স্ক্রিন + `EditorChrome`/`EditorModals` কম্পোজেবলে ভাগ; `TransformView` (১,০৮৪) ভাঙুন।
13. Compose UI টেস্ট লিখুন (আগেই থাকা `testTag` ব্যবহার করে): import → split → delete → export-ডায়ালগ হ্যাপি পাথ।
14. `LaneLayout` ও `outputSize()`-এর ডিরেক্ট ইউনিট টেস্ট; `EditorPlayer`-এর timeline↔playlist ম্যাপিং টেস্ট (Robolectric)।
15. এক্সপোর্ট: ফোরগ্রাউন্ড সার্ভিস + নোটিফিকেশন + ক্যানসেল; `FileProvider` (API ≤ ২৮)।
16. R8 চালু + WebP/`drawable-nodpi`।

---

## ১১. যা আসলেই প্রশংসনীয় 👏

- **একটিই ক্লক।** `play()`-এর ভেতরে “ExoPlayer-ই একমাত্র ক্লক, টাইমার শুধু ফলব্যাক” — ভিডিও এডিটরের সবচেয়ে সাধারণ বাগ (ডুয়াল ক্লক ড্রিফট) এড়ানো হয়েছে, আর কেন এমন করা হয়েছে তা কমেন্টে লেখা আছে।
- **`LaneLayout`** — পিওর, টেস্টেবল, নিয়মগুলো ডকুমেন্টেড; “media কোনও বিশেষ রো নয়” দর্শনটি সাহসী ও সঠিক।
- **`EditorPlayer.signature`** (`:31-47`) — অপ্রয়োজনীয় `setMediaItems` এড়ায়; ছোট কিন্তু বুদ্ধিদীপ্ত।
- **`VideoExporter`** — MediaStore-এ `IS_PENDING` সহ সঠিক রাইট, বড় ফাইল কপি মেইন থ্রেডের বাইরে, `Transformer` ক্যানসেল `DisposableEffect`-এ।
- **`rememberUpdatedState` + `pointerInput(Unit)`** (`TimelineLayerPanel.kt:118-134`) — “জেসচার ব্লক একবার তৈরি, ড্র্যাগের সময় রিস্টার্ট হবে না” — বাস্তব বাগ ঠিক করার চিহ্ন।
- **লগ/প্রিন্টন নেই**, `@UnstableApi` সঠিকভাবে স্কোপড, `buildFeatures.buildConfig = true`।
- **২০টি মিনিংফুল ইউনিট টেস্ট** যেগুলো আসল ইনভ্যারিয়েন্ট (“কোনও লেনেই ওভারল্যাপ নেই”) চেক করে — AI-জেনারেটেড বা তাড়াহুড়ো প্রজেক্টে বিরল।

---

---

## ১২. ক্লিনআপ পাস (২০২৬-১০-০৮) — কী করা হয়েছে

### সরানো ডেড কোড
| কী | লাইন |
|---|---|
| `EditShapeView.kt`, `AppliedEffectsView.kt`, `CropView.kt`, `ClipToolsSheets.kt`, `MediaPreview.kt` | ২,৮০৬ |
| `EditorSheets.kt` থেকে `AddAudioSheet`/`AddTextSheet` | ১২৮ |
| `VideoEditorScreen.kt` থেকে ৯টি অপ্রাপ্য `show*` ফ্ল্যাগ + ব্লক | ১৮৯ |
| ViewModel-এর অব্যবহৃত মেথড (`updateClipCrop`, `resetClipTransform`, `toggleTrackMute`, `setClipEffect/Animation/BlendMode/Color`, `replaceSelectedClipMedia`) | ১৪০ |
| `TimelineClip`/`TimelineTrack`-এর অব্যবহৃত ফিল্ড (crop × ৫, blend, effect, animation, volume, drawableResId, isLocked × ২, track.isMuted) | ১৩ |
| ৬টি ডেমো drawable (~৫.৮ MB) + `player_texture.xml` | — |
| `libs.versions.toml` থেকে ~৩৩ অব্যবহৃত এন্ট্রি + Gemini স্ক্যাফোল্ডিং (`metadata.json`, `.env.example`) | ৫১ |
| ৫৭টি অব্যবহৃত import | ৫৭ |
| **মোট** | **≈ ৩,৩৮০ লাইন + ৫.৮ MB** |

### ছোট ফিক্স
- `splitSelectedClip()` এখন `Boolean` রিটার্ন করে → প্লেহেড ক্লিপের বাইরে থাকলে আর “স্প্লিট হয়েছে” মিথ্যে বার্তা আসে না।
- ব্যাক বাটনের “Project autosaved” → “Project is not saved yet — use Export…”。
- Export ডায়ালগ: হার্ডকোডড “1080p FHD (1920x1080)” ও বানানো “28.4 MB” সরিয়ে **আসল** আউটপুট সাইজ / রেশিও / ফরম্যাট; সাইজ ম্যাথ `ExportSizes`-এ (এনকোডার ও UI একই সোর্স)।
- Undo হিস্ট্রি ৫০-এ ক্যাপড।
- API ২৪–২৮-এ `Uri.fromFile` → `FileProvider` (`res/xml/file_paths.xml` আগে থেকেই ছিল, ব্যবহার হতো না) + ম্যানিফেস্টে provider。
- নতুন `ExportSizesTest` (৫টি টেস্ট)।

### যাচাই (CI ছাড়া)
স্যান্ডবক্সে Android SDK নেই, তাই:
1. **Kotlin 2.2.10** (প্রজেক্টের একই ভার্সন) দিয়ে পুরো `app/src/main` কম্পাইল → **কোনো syntax error নেই**; error ৫,৪২৯ → ৩,২৫৫ (−৪০%), কোনো **নতুন error-ক্যাটাগরি নেই** (বাকি সব অনুপস্থিত AndroidX/Compose/Media3 থেকে)।
2. `androidx.lifecycle` + `kotlinx.coroutines`-এর স্টাব বানিয়ে **আসল `VideoEditorViewModel` + আসল ২০টি টেস্ট** কম্পাইল ও রান → **২৪/২৪ পাস** (১৯ পুরনো + ৫ নতুন)।

### ⚠️ CI নোট
এই সেশনের GitHub টোকেনের `workflows`/`actions` পারমিশন নেই, তাই (ক) `.github/workflows/build.yml`-এ `pull_request` ট্রিগার যোগ করা যায়নি, (খ) `gh workflow run` দিয়ে বিল্ড ট্রিগার করা যায়নি (HTTP 403)। **ব্যবহারকারীকে手动 চালাতে হবে:** GitHub → Actions → *Build APK* → *Run workflow* → branch `arena/71cd0be0-tracket`। ওয়ার্কফাইলে `workflow_dispatch` আগে থেকেই আছে, তাই এতে APK + `gradle testDebugUnitTest` চলবে।

**সারকথা:** ইঞ্জিনের ভিত্তি শক্ত, কিন্তু UI সেই ভিত্তির চেয়ে অনেক এগিয়ে — ভিত্তি (export parity, persistence, drag/trim, honest feedback) দ্রুত শক্ত করলে এই প্রজেক্ট সত্যিই একটি শক্তিশালী মোবাইল এডিটর হতে পারবে। C++ মাইগ্রেশনের কথা ভাবার আগে Phase 1-এর মিউজ-রিলেটেড ফাঁকগুলো ভরাট করাই বুদ্ধিমানের কাজ।
