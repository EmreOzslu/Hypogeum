# Hypogeum

Tur bazlı, co-op XCOM tarzı strateji oyunu. Tasarım: [`docs/DESIGN.md`](docs/DESIGN.md).

## Modüller

| Modül | İçerik | Durum |
|---|---|---|
| `:domain` | Saf Kotlin. RNG, iki kampüs, ortak dünya, çözüm sırası, ortak görevler, sevkiyat, araştırma, taktik motor | M0–M5 |
| `:data` | Kayıt codec'i, kanonik JSON, state digest, lockstep senkron (LocalHotseatSync) | M0, M3 |
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

Taktik faz sırası: P1 → P2 (varsa) → uzaylılar. Komutları yalnızca o anki fazın sahibi verir (`move`, `shoot`, `end`, `status`).

## Test

```sh
gradle test
```
