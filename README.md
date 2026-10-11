# Hypogeum

Tur bazlı, co-op XCOM tarzı strateji oyunu. Tasarım: [`docs/DESIGN.md`](docs/DESIGN.md).

## Modüller

| Modül | İçerik | Durum |
|---|---|---|
| `:domain` | Saf Kotlin. RNG, kampüs (inşaat, üretim, asker), sevk, taktik kural motoru | M0, M1, M2 |
| `:data` | Kayıt codec'i, kanonik JSON, state digest | M0 |
| `:cli` | Terminal arayüzü: kampüs yönetimi + taktik görevler, tek oyunculu | M1, M2 |

Android/Compose arayüzü (`:app`) henüz yok; Android SDK bu ortamda kurulu değil.

## Çalıştırma

```sh
gradle :cli:installDist
./cli/build/install/cli/bin/cli 42        # 42 = seed
```

Planlama fazında: `build <slot> <reactor|foundry|hydro|barracks>`, `recruit <rifleman|heavy|medic>`,
`dispatch <bölge> <asker-id>...`, `draft`, `clear`, `commit`. `commit` taslağı uygular ve epoch'u bitirir.
Sevk edilen bir görev başlarsa taktik komutlar devreye girer: `move <id> <x> <y>`, `shoot <id> <hedef>`, `end`, `status`.
Görev bitince sonuç (ölüm, yaralanma, ödül) uygulanır ve yeni epoch başlar.

## Test

```sh
gradle test
```
