# Hypogeum

Tur bazlı, co-op XCOM tarzı strateji oyunu. Tasarım: [`docs/DESIGN.md`](docs/DESIGN.md).

## Modüller

| Modül | İçerik | Durum |
|---|---|---|
| `:domain` | Saf Kotlin. RNG, iki kampüs, ortak dünya, çözüm sırası, ortak görevler, sevkiyat, araştırma, taktik motor | M0–M5 |
| `:data` | Kayıt codec'i, kanonik JSON, state digest, lockstep senkron, sıralı/yeniden iletimli kanal | M0, M3, M6 (protokol) |
| `:cli` | Terminal arayüzü: tek cihazda iki koltuk (hotseat) | M1–M5 |

Android/Compose arayüzü (`:app`) henüz yok; Android SDK bu ortamda kurulu değil.

## Çalıştırma

```sh
gradle :cli:installDist
./cli/build/install/cli/bin/cli 42        # 42 = seed
```

Tek cihazda iki koltuk (hotseat) sırayla oynar. Her koltuk kendi planını yazar ve `commit` eder;
ikinci commit'ten sonra epoch çözülür. Koltuk yalnızca kendi kampüsünü görür, dünya ortaktır.

Planlama: `build <slot> <reactor|foundry|hydro|barracks>`, `recruit <rifleman|heavy|medic>`,
`dispatch <bölge> <asker-id>...`, `claim <düğüm>`, `draft`, `clear`, `status`, `commit`.
`claim` aynı düğüme iki koltuk de uzanırsa, seed'li sırada önce gelen kazanır; kaybeden komut reddedilir ve ödeme yapmaz.

Ekonomi: `ship <alloy> <cells> <biomass> [asker-id...]` kaynak ve asker gönderir (röle gerekir). Taşıma kaybı %20, yukarı yuvarlanır;
varış iki epoch sonra. Hat kapasitesi epoch başına iki sevkiyat, iki koltuk arasında paylaşılır.
`research <ballistics|reinforced_hulls|field_medicine>` (lab gerekir, aynı anda tek araştırma). Röle olmayan kampüs izole olur:
düğüm verimi yarıya iner. Infirmary yaralıların iyileşmesini hızlandırır.

Aynı epoch'ta aynı bölgeye sevk eden iki koltuğun kadroları tek ortak görevde birleşir. Bir koltuk epoch başına tek kadro sevk eder.

Savunma: bir bölgeye uyarı verildikten sonra (tehdit en yükseğe ulaştığında) saldırı bir sonraki epoch'un çözümünde iner.
Bölgenin sahiplerinin hazır askerleri (yaralı olmayan, başka görevde olmayan) savunma görevi oluşturur. Savunmayı kazanırsan
sahiplik korunur ve tehdit düşer; kaybedersen sahiplik düşer ve her kampüsten rastgele bir tesis yıkılır.
Sahipsiz bölgeye saldırı ise yalnızca tehdidi sıfırlar.

Taktik faz sırası: P1 → P2 (varsa) → uzaylılar. Komutları yalnızca o anki fazın sahibi verir (`move`, `shoot`, `end`, `status`).

## Test

```sh
gradle test
```

## Ağ durumu

`ReliableChannel` (sıra numarası, tek teslim, boşluk algılama, yeniden iletim, sürüm kontrolü) ve
`UnreliableBus` ile test edilen kayıp/tekrar/kopma senaryoları hazır. Gerçek Firestore adaptörü (`FirebaseSync`)
henüz yazılmadı: Firebase projesi ve emulator bu ortamda yok. Adaptör, `Link<WireMessage>` arayüzünü
Firestore belgeleri üzerinden uygulayacak; protokol değişmeyecek.
Bilinen sınır: giden mesajlar bellekte tutuluyor (kırpma yok), snapshot tabanlı resync henüz yok.

## Android APK (`:apk`)

`:apk` Android çerçeve görünümleriyle yazılmış tek cihazlı iki koltuk arayüzüdür (Compose değil).
APK, Google'ın derleme zinciri (AGP, aapt2, r8) olmadan `scripts/build_apk.py` ile üretilir:

```sh
python3 scripts/build_apk.py
# APK: apk/build/outputs/apk/debug/app-debug.apk
```

Betik şunları yapar: Gradle ile sınıfları derler; dalvik-dx 16.0.1 ile `classes.dex` üretir; ikili
`AndroidManifest.xml` yazar; debug anahtarıyla v1 imzalar. Araçlar Maven Central'dan indirilir.

Sınırlar (bilinçli):

- Yalnızca v1 imza; `targetSdk` 29. v2/v3 imza ve zipalign yoktur (aapt2/apksigner Google depolarında).
- Kaynak (res) yok; uygulama adı ve metinler kodda.
- Compose sürümü (önceki `:app`) bu yolda derlenemiyor; Google'ın araçları kullanılabilir olunca geri dönebilir.
- Kotlin 2.0 derlemesi, dx'in desteklediği sınıf-tabanlı lambda ve eski stdlib (1.7.22) ile çalışır. Betik,
  uygulama sınıflarının başvurduğu kotlin/kotlinx sınıflarının çalışma zamanı jar'larında var olduğunu denetler
  (metot düzeyi denetim yok).
- Cihazda ve emülatörde çalıştırılmadı; yalnızca paketin yapısı ve imzası doğrulandı.
