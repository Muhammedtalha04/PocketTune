# PocketTune — Türkçe

PocketTune, Minecraft 1.21.4 / NeoForge 21.4.x için geliştirilmiş, YouTube video ve playlistlerini senkronize hoparlörlerden çalan açık kaynaklı bir moddur. Kuyruk, hoparlör ayarları ve zaman çizelgesi sunucu tarafından yönetilir; ses her dinleyen istemcide yerel `yt-dlp` ve `mpv` süreçleriyle oynatılır.

## Öne çıkan özellikler

- Video/playlist ekleme, yüksek kaliteli kapaklar, sürükle-bırak sıralama ve parça menüsü
- Ses seviyesi, 4–128 blok menzil, farklı azalma eğrileri, duvar engellemesi ve ekolayzır
- Tüm GUI Scale değerlerine uyumlu modern ekran ve sağ üst müzik overlay'i
- F1 altında overlay görünürlüğü için config seçeneği
- ESC menüsü ve alt menülerinde doğru duraklatma; envanter/sandık/mod GUI'lerinde kesintisiz ses
- Hoparlörü durumunu kaybetmeden envantere alma ve yeniden yerleştirme
- İsteğe bağlı menzil/duvar halkaları ile event, packet, BlockEntity ve BlockState debug araçları

![PocketTune arayüzü](images/player-screen.png)

## Oyuncu kurulumu

1. Minecraft 1.21.4 için NeoForge 21.4.x kur.
2. Güncel `yt-dlp` ve `mpv` kurup `PATH` içine ekle.
3. PocketTune JAR dosyasını `mods` klasörüne koy.
4. Hoparlörü yerleştir; normal sağ tıkla GUI'yi aç, Shift + sağ tıkla durumunu koruyarak envantere al.

Araçlar bulunamazsa `config/pockettune-common.toml` içinde mutlak yollarını belirtebilirsin. Ayrıntılar: [Configuration](CONFIGURATION.md) ve [Troubleshooting](TROUBLESHOOTING.md).

## Tek komutla geliştirme ortamı

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File .\setup.ps1
```

macOS/Linux:

```bash
./setup.sh
```

Betik Git, JDK 21, `yt-dlp` ve `mpv` eksiklerini algılar; desteklenen paket yöneticisiyle kurar, yerel geliştirme config'ini hazırlar ve Minecraft'ı açmadan testleri çalıştırır.

## Açık kaynak ve destek

PocketTune [Apache-2.0](../LICENSE) lisanslıdır. Oyunda, sunucuda ve mod paketinde kullanılabilir; fork edilebilir, değiştirilebilir ve başka bir modun temeli yapılabilir. Dağıtımlarda lisans ile [`NOTICE`](../NOTICE) atfını koru; README'de [orijinal proje](https://github.com/Muhammedtalha04/PocketTune) ve [geliştirici profiline](https://github.com/Muhammedtalha04) bağlantı verilmesi memnuniyetle karşılanır.

Proje her zaman ücretsiz kalacaktır. İsteğe bağlı destek: [Buy Me a Coffee](https://buymeacoffee.com/mtktalha).
