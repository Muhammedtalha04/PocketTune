# PocketTune proje durumu

- Son güncelleme: 16 Temmuz 2026
- Sürüm: `0.7.1`
- Minecraft: `1.21.4`
- NeoForge: `21.4.157`
- Java: `21`
- Lisans: `Apache-2.0`

## Sonuç

Milestone 7 kod, otomatik test, production build ve açık kaynak depo hazırlığı düzeyinde tamamlandı. Kullanıcının isteği gereği Minecraft, gerçek `yt-dlp` çözümleme testi ve gerçek `mpv` oynatma testi çalıştırılmadı. Aşağıdaki etkileşimli senaryolar kullanıcı tarafından oyun içinde doğrulanmalıdır.

## Tamamlanan kapsam

- Modern ve responsive hoparlör GUI'si; geniş/kompakt yerleşim ve net sürükle-bırak önizlemesi
- İngilizce oyun dili için GUI, config, overlay, tooltip, bildirim, hata ve debug metinlerinin tamamı İngilizce
- Portable hoparlör zaman çizelgesi güncellenirken eldeki eşyanın tekrar tekrar aşağı-yukarı oynamasını engelleyen re-equip filtresi
- Yüksek kaliteli YouTube kapak zinciri, playlist metadata'sı ve GUI içi tek seferlik bildirimler
- Dışa/İçe Aktar arayüzü ve ilgili kodun kaldırılması
- Normal sağ tıkta GUI, Shift + sağ tıkta sunucu-doğrulamalı portable pickup
- Creative/Survival blok kaldırma, vanilla etkileşim paketi yarışı ve yerleştirme kimliği düzeltmeleri
- ESC ile başlayan bütün pause alt-menü zincirinde duraklatma; diğer GUI'lerde kesintisiz çalma
- Sağ üst portable müzik overlay'i, GUI Scale/çözünürlük uyumu ve F1 config seçeneği
- İlk saniye tekrarı/sessiz başlangıç için sessiz-paused hazırlama, doğrulanmış seek ve sınırlı retry
- Sunucu-otoriteli kuyruk, zaman çizelgesi, ayarlar, playlist ilerletme ve paket doğrulama
- Menzil/azalma/duvar engellemesi/EQ ve daha görünür test halkaları
- Event, packet, callback, BlockEntity ve BlockState senkron debug araçları
- Makineye özel `yt-dlp`/`mpv`, istemci ve sunucu config ayrımı
- Eşzamanlı mpv süreç sınırı, bounded executor/kuyruk/cache/log ve yarış-güvenli cleanup
- Yerleştirme UUID'si, yüklü chunk, reach/permission, operation ID ve payload sınır kontrolleri
- Windows/macOS/Linux tek komut geliştirme kurulumu
- Codex, Claude Code, Cursor, Windsurf ve Cline için ortak AI-first kurallar/dokümantasyon
- Apache-2.0 lisansı, kalıcı `NOTICE` atfı, katkı/güvenlik/issue/PR belgeleri
- Güncel İngilizce arayüz, portable overlay, elde taşınan hoparlör ve config ekranlarından oluşan GitHub galerisi

## Otomatik doğrulama

- `clean test --rerun-tasks --no-daemon --no-configuration-cache`: başarılı
- JUnit: 39 suite, 145 test, 0 failure, 0 error, 0 skipped
- `clean build --rerun-tasks --no-daemon --no-configuration-cache`: başarılı
- JAR metadata: `pockettune` / `0.7.1` / `Apache-2.0`
- JAR içinde lisans, `NOTICE` ve NeoForge metadata'sı: doğrulandı
- Test/example sınıfları production JAR içinde: yok
- Build JAR ile `libs` kopyası byte-for-byte aynı

- Production JAR: `libs/pockettune-0.7.1.jar`
- Boyut: `412930` byte
- SHA-256: `82311342491FF900FD744B5C6A86CDA166CA25A4DC3D6BC31F0E18D01862B7D6`

## Oyun içi regresyon listesi

1. Normal sağ tık GUI'yi tek kez açmalı ve eldeki blok aynı tıklamayla yerleşmemeli; sneak sağ tık GUI açmamalı.
2. Creative ve Survival'da hoparlörü art arda en az beş kez yerleştir/topla; blok geri gelmemeli, kopya item oluşmamalı.
3. ESC → Options/Mods ve alt ekranlarda müzik durmalı; Back to Game sonrasında aynı konumdan sürmeli. Envanter/sandık/crafting/PocketTune GUI'sinde sürmeli.
4. Playlist satırı sürüklenirken kaynak vurgusu, hareketli önizleme ve hedef ayırıcı çizgisi doğru sırayı göstermeli.
5. Test modunda tam ses, azalma, menzil sınırı ve duvar ışınları farklı renklerle eksiksiz görünmeli.
6. Blok Debug HUD/log, pickup request kimliğiyle event → packet → callback → BlockEntity → client/server BlockState sırasını göstermeli.
7. GUI Scale Auto/1x/2x/3x/4x ve 16:9/ultrawide pencerelerde overlay ekran içinde ve okunaklı kalmalı.
8. F1 overlay seçeneği açık/kapalı iki durumda doğru davranmalı.
9. Yeni yerleştirilen hoparlörde ilk parça birkaç kez başlatılmalı; ilk saniye tekrarı veya sessiz/takılı başlangıç olmamalı.
10. Video/playlist ekleme sonucu yalnız GUI bildiriminde bir kez görünmeli; GUI yeniden açıldığında eski bildirim tekrarlanmamalı.
11. Sunucu maximum range runtime config değişikliğinden sonra yüklü hoparlörün menzili yeni sınıra düşmeli.
12. Dünya/chunk değişimi ve çıkış sonrasında eski mpv sesi veya yetim `pockettune-runtime` süreci kalmamalı.
13. Portable hoparlörü elde en az 30 saniye tut; zaman çizelgesi güncellenirken eşya periyodik olarak aşağı-yukarı oynamamalı.

Bir regresyon görülürse `docs/TROUBLESHOOTING.md` içindeki güvenli hata raporu adımlarını ve GitHub bug şablonunu kullan.
