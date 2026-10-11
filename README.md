# Hypogeum

Tur bazlı, co-op XCOM tarzı strateji oyunu. Tasarım: [`docs/DESIGN.md`](docs/DESIGN.md).

## Modüller

| Modül | İçerik | Durum |
|---|---|---|
| `:domain` | Saf Kotlin. RNG, iki kampüs, ortak dünya, çözüm sırası, sevk, taktik kural motoru | M0–M3 |
| `:data` | Kayıt codec'i, kanonik JSON, state digest, lockstep senkron (LocalHotseatSync) | M0, M3 |
| `:cli` | Terminal arayüzü: tek cihazda iki koltuk (hotseat) | M1–M3 |

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

Sevk edilen görevde taktik faz başlar; komutları yalnızca görevin sahibi verir (`move`, `shoot`, `end`, `status`).

## Test

```sh
gradle test
```
