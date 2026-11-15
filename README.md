# LogSU

Root yetkili, gizlilik odaklı Android çağrı kayıt, ortam dinleme, ekran izleme ve sistem koruması (emanet modu) denetim altyapısı.

## Proje Özeti ve Yetenekler

LogSU, cihaz sahibinin tam denetimi altında yerel olarak çalışan, üçüncü taraf bulut servislere hiçbir veri göndermeyen güvenlik ve denetim uygulamasıdır.

### 1. Çift Kanallı Çağrı Kaydı (GSM ve WhatsApp VoIP)
- Standart GSM şebeke aramaları ve WhatsApp sesli VoIP aramaları otomatik olarak tespit edilip kaydedilir.
- Sol Kanal: Cihaz mikrofonu (yerel ses).
- Sağ Kanal: Karşı tarafın downlink / hat sesi.
- Oynatıcıda kanal kontrolleri: Aramalarda Mikrofon ve Arayan kanallarını ayrı ayrı açıp kapatabilme desteği.

### 2. Bağımsız Ortam ve Toplantı Ses Kaydı
- Arayüzden tek dokunuşla başlatılan temiz mono 44.1 kHz PCM16/WAV ortam kaydedici (`CallMonitorService` ve `AudioCaptureController`).
- Cihaz galerisine (`DCIM/LogSU`) LogSU amblemli dikey MP4 videosu olarak dışa aktarım desteği (`GalleryExporter`).

### 3. Emanet Modu (Sistem Koruması)
Cihaz geçici olarak başkasına emanet verildiğinde bildirim çubuğundaki Hızlı Ayarlar (Quick Settings) "Koruma" butonu üzerinden (`GuardTileLaunchActivity` ve `GuestGuardService`) veya uygulama içinden tek dokunuşla başlatılır:
- **Root Seviyesinde Sessiz Ekran Kaydı:** `GuestGuardService` ve `RootDisplayRecorder` altyapısı ile root `app_process` üzerinden `1080x2400 @ 30fps High Profile 10 Mbps` MP4 video kaydı. `VirtualDisplay` ve `FLAG_SECURE` destekli özel kayıt motoru sayesinde güvenlikli pencerelerde siyah ekran sorunu yaşanmaz.
- **Akıllı Ekran Yönetimi:** Ekran kapandığında video kaydı duraksatılır; arka planda kesintisiz mono ortam ses kaydı sürdürülür ve ekran tekrar açıldığında yeni video segmenti kaydedilip çıkışta birleştirilir.
- **Görünmez Dokunmatik Takibi:** Ekranda geliştirici izi açılmadan `/dev/input/event*` aygıtları üzerinden ham dokunmatik koordinatları (`.touch`) kaydedilir.
- **Periyodik Kamera ve Pano Loglama:** Camera2 altyapısı ile olay/periyot bazlı sessiz kamera kontrolü ve emanet modu boyunca kopyalanan metinlerin pano (clipboard) kaydı.

### 4. Güvenlik ve Erişim Kontrolü
- **Biyometrik ve PIN Koruması:** Uygulama açılışında parmak izi veya kilit ekranı PIN/desen doğrulaması zorunludur.
- **Oturum Güvenliği:** `AppSessionLock` ile arka plana geçişte anında oturum kilitlenir.
- **Ekran Görüntüsü Koruması:** Arayüzlerde `FLAG_SECURE` aktiftir, son uygulamalar ekranında önizleme oluşmaz.

### 5. Veri Saklama ve Yerel Kayıt Özeti
- **Room Otomatik Temizlik:** Rehberde kayıtlı numaralar için 3 gün, bilinmeyen numaralar için 7 gün saklama süresi uygulanır (`RetentionWorker`). Delil olarak kilitlenen aramalar ve ortam ses kayıtları otomatik temizlikten muaf tutulur.
- **Çevrimdışı Kayıt Özeti:** `OfflineSpeechTranscriber`, ağ bağlantısına ihtiyaç duymadan cihaz üzerinde WAV ses enerjisi tabanlı konuşma aktivitesi özeti çıkarır.

---

## Proje Mimarisi

```
com.ktarxhun.logsu
├── data
│   ├── db                  # Room veritabanı ve DAO katmanı
│   ├── model               # CallRecord, GuestSession, GuestEvent, TouchEvent
│   └── repository          # RecordRepository veri deposu
├── root
│   ├── RootShellManager    # libsu tabanlı root erişim yöneticisi
│   ├── AudioCaptureController # Çift kanallı çağrı ve mono ortam ses kaydedici
│   ├── ScreenRecordController # Arka plan ekran kayıt kontrolcüsü
│   ├── RootDisplayRecorder # app_process tabanlı 1080x2400 30fps MediaCodec ekran kaydedici
│   └── TouchCaptureController # /dev/input/event* dokunma izleme servisi
├── service
│   ├── CallMonitorService  # Arama ve ortam ses kaydı ön plan servisi
│   ├── CallReceiver        # GSM arama yayın alıcısı
│   ├── GuestGuardService   # Emanet modu orkestratörü
│   ├── GuardTileLaunchActivity # Hızlı ayarlar trambolin aktivitesi
│   ├── SystemProtectionTileService # Quick Settings bildirim butonu
│   ├── VoipAccessibilityService # WhatsApp arama erişilebilirlik servisi
│   ├── BootReceiver        # Cihaz açılış tetikleyicisi
│   └── RetentionWorker     # Otomatik temizlik WorkManager işçisi
├── stt
│   └── OfflineSpeechTranscriber # Çevrimdışı ses enerjisi tabanlı konuşma özeti
├── ui
│   ├── MainActivity        # Biyometrik korumalı ana giriş
│   ├── auth                # Biyometrik ve PIN doğrulama (AppSessionLock)
│   ├── dashboard           # Aramalar, Ses Kaydı ve Emanet paneli
│   ├── player              # LogSuPlayerActivity (Çift kanal mikser ve dokunma katmanı)
│   ├── settings            # Ayarlar ve veri yönetimi
│   ├── components          # Arayüz bileşenleri
│   └── theme               # Renk ve tema tanımları
└── util
    └── GalleryExporter     # DCIM/LogSU MP4 galeri aktarımı
```

---

## Derleme ve Kurulum

### Gereksinimler
- Android SDK (API Seviyesi 34)
- JDK 17 veya üzeri
- Root yetkisi verilmiş Android cihazı (Magisk veya KernelSU)

### Derleme Adımları
```bash
# Debug derleme
./gradlew assembleDebug

# Release derleme
./gradlew assembleRelease
```

### Cihaza Yükleme
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Root Yetkilendirme (Magisk / KernelSU)
LogSU ilk çalıştırıldığında ekranda standart root erişim izin penceresi açılır. Magisk veya KernelSU üzerinden `com.ktarxhun.logsu` paketine kalıcı root erişimi onaylanmalıdır.

Gerekli özel izinleri komut satırı üzerinden sağlamak için:
```bash
adb shell "su -c 'pm grant com.ktarxhun.logsu android.permission.CAPTURE_AUDIO_OUTPUT; pm grant com.ktarxhun.logsu android.permission.MODIFY_AUDIO_ROUTING; appops set com.ktarxhun.logsu MANAGE_EXTERNAL_STORAGE allow'"
```

---

## Lisans

Bu proje MIT Lisansı ile lisanslanmıştır. Ayrıntılar için [LICENSE](LICENSE) dosyasına bakabilirsiniz.

Copyright (c) 2026 ktarxhun

---

## Yasal Uyarı ve Sorumluluk Reddi

LogSU, yalnızca cihaz sahibinin kendi cihazını denetlemesi, kişisel güvenliği ve yasalara uygun yerel veri yedeklemesi amacıyla geliştirilmiştir. Ses, arama veya görüntü kayıtlarının karşı tarafın veya üçüncü kişilerin rızası olmadan alınması, yerel yasalara göre suç teşkil edebilir. Bu yazılımın hukuka aykırı şekilde veya ilgili kişilerin haberi ve onayı olmaksızın kullanılmasından doğabilecek tüm hukuki ve cezai sorumluluk tamamen kullanıcıya aittir. Geliştirici, yazılımın amacı dışında veya kötüye kullanımından sorumlu tutulamaz.
