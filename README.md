# PDF Studio (Kotlin + Jetpack Compose)

Android Studio (Koala+) ilə qovluğu açın → Gradle sync → Run.
minSdk 26 · Kotlin 1.9.24 · Compose BOM 2024.06

## Quruluş
- core/PdfOps.kt        – PDF redaktə (mətn, vurğu, çəkmə, ört, fırlat, sil, birləşdir, görünməz OCR qatı)
- core/Ocr.kt           – OcrEngine interfeysi + ML Kit (Tesseract ilə əvəz edilə bilər)
- core/DocumentFormat.kt– Yeni format dəstəyi üçün plugin nöqtəsi (DocumentFormats.all)
- core/PageRenderer.kt  – PdfRenderer ilə səhifə göstərilməsi
- ui/*                  – Home, Editor, Theme
- res/drawable/ic_launcher_* – tətbiq ikonu

## Qeyd
Şrift: PdfOps assets/fonts/font.ttf faylını (ə, ş, ğ dəstəkləyən, məs. NotoSans) axtarır, yoxdursa sistem şriftindən istifadə edir.

## GitHub-da APK yığmaq
1. Yeni repo yaradın və bu qovluğun məzmununu push edin.
2. **Actions → Build APK** işləyəcək (və ya "Run workflow" ilə əl ilə başladın).
3. Bitəndə run səhifəsinin **Artifacts** hissəsindən `PDF-Studio-apk` yükləyin.
4. `git tag v1.0 && git push origin v1.0` etsəniz, APK avtomatik **Releases** bölməsinə də qoyulur.
Qeyd: bu debug-imzalı APK-dır, telefona birbaşa quraşdırıla bilər ("naməlum mənbə" icazəsi lazımdır).

## Səhifə üzərində inline mətn redaktəsi
"Redaktə" alətini seçin → abzaslar çərçivə ilə görünür → abzasa toxunun → kursor mətnin içində görünür, klaviaturadan yazın.
- Dəyişməyən sətirlər PDF-də ƏLLƏNMİR (100% eyni qalır). Yalnız toxunulan sətirlər yenidən yazılır.
- Yazı PDF-in ORİJİNAL (daxili) şriftinin kodları ilə yazılır; ölçü, rəng, sətir məsafəsi qorunur.
- Şrift subset olduğu üçün orijinal şriftdə olmayan hərf yazsanız, yalnız o hərf Noto şriftlə çəkilir.
- Seçim: sürüşdürün; söz seçmək: uzun basın. B/I/ölçü/rəng seçilmiş hissəyə tətbiq olunur.
- Önizləmə gerçək PDF render-idir (PdfRenderer), ona görə nəticə saxlamazdan əvvəl də olduğu kimi görünür.
- Skan (şəkil) PDF-lərdə mətn qatı yoxdur, redaktə mümkün deyil (əvvəl OCR → axtarıla bilən PDF).
