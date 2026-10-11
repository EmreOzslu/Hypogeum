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

## Android APK (`:app`)

`:app` Compose arayüzüdür (tek cihazda iki koltuk). Android Gradle eklentisi ve Compose kütüphaneleri Google
depolarından (`maven.google.com`, yönlendirmeleri `dl.google.com`) gelir. Varsayılan derleme bunlara bağlı değildir;
modül yalnızca `-Pandroid=true` ile dahil edilir.

Gereksinimler:

- Ortamda `dl.google.com` erişilebilir olmalı.
- Android SDK kurulu olmalı (platform 34 ve build-tools 34.0.0); `ANDROID_HOME` ya da `local.properties` ile gösterilmeli.

```sh
gradle -Pandroid=true :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Durum: arayüz kodu ve Gradle yapılandırması yazıldı; bu ortamda Android SDK ve Google depoları erişilemediği için
derlenip test edilmedi. Oturum mantığı (`HotseatSession`) JVM tarafında test edilmiştir.

