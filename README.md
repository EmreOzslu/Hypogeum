# Hypogeum

Tur bazlı, co-op XCOM tarzı strateji oyunu. Tasarım: [`docs/DESIGN.md`](docs/DESIGN.md).

## Modüller

| Modül | İçerik | Durum |
|---|---|---|
| `:domain` | Saf Kotlin. RNG, taktik kural motoru (harita, LOS, siper, AP, uzaylı AI) | M0 + M1 kuralları |
| `:data` | Kayıt codec'i, kanonik JSON, state digest | M0 |
| `:cli` | Terminal arayüzü, tek cihazda oynanabilir taktik skirmish | M1 |

Android/Compose arayüzü (`:app`) henüz yok; Android SDK bu ortamda kurulu değil.

## Çalıştırma

```sh
gradle :cli:installDist
./cli/build/install/cli/bin/cli 42        # 42 = seed
```

Komutlar: `move <id> <x> <y>`, `shoot <id> <hedef>`, `end`, `status`, `help`, `quit`.
Oyuncu birimleri `p1..p3` (ekranda `1..3`), uzaylılar `a1..a3` (ekranda `A..C`).

## Test

```sh
gradle test
```
