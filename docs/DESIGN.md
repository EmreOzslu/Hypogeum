# Hypogeum — Tasarım Dokümanı

> Durum: taslak v0.1 · Kapsam: mimari, veri modeli, tur sistemi, co-op senkronizasyon, milestone planı.
> Bu doküman kod içermez. Kotlin bloklarındaki yapılar **sözleşme** niteliğindedir (alan adları ve tipler), implementasyon değildir.

---

## 0. Temel ilkeler

Tasarımın geri kalanı dört ilkeden türüyor. Bunlardan biri çiğnenirse diğerleri de çöker.

1. **Oyun durumu tek bir değerdir.** `GameState` immutable bir değerdir. Her değişiklik `apply(state, command) -> (state', events)` saf fonksiyonuyla olur. Yan etki, zaman, ağ, rastgelelik (state içindeki RNG hariç) domain'e girmez.
2. **Senkronizasyon durum değil, komut akışıyla yapılır.** İki cihaz aynı başlangıç durumundan ve aynı sıralı komut logundan geçerse aynı durumu üretir. Durumları birbirine "merge" etmeye çalışmıyoruz.
3. **Determinizm açıkça korunur.** Her rastgele sayı tek bir seed'li RNG'den, sabit bir sırayla çekilir. Floating point, `HashMap` iterasyonu, sistem saati, UUID domain'de yasaktır.
4. **Eşzamanlılık hamle sahipliğiyle çözülür.** Stratejide aynı kaynağa iki oyuncunun aynı anda uzanması, kurallı bir sıralama ve reddetme ile çözülür. Taktikte her fazın tek sahibi vardır, dolayısıyla çakışma yapısal olarak yoktur.

---

## 1. Paket ve modül yapısı

Katman sınırını derleyici zorlasın diye **Gradle modülleri** kullanıyoruz. `:domain` Android plugin'ine sahip olamaz; yanlışlıkla `android.*` import edilirse build kırılır.

```
Hypogeum/
├── settings.gradle.kts
├── domain/                      # kotlin("jvm"). Android bağımlılığı YOK.
│   └── src/main/kotlin/hypogeum/domain/
│       ├── core/                # Rng, Pos, PerPlayer, Id üretimi, Result
│       ├── content/             # Denge verisi: tesis, asker sınıfı, araştırma ağacı, uzaylı tipleri
│       ├── world/               # WorldState, Region, Incursion, ThreatModel, TransitLink
│       ├── campus/              # Campus, Facility, Resources, ProductionOrder, ResearchState
│       ├── roster/              # Soldier, Stats, Loadout, Injury
│       ├── strategy/            # StrategicCommand, Upkeep, Planning, Resolution, Aftermath
│       ├── tactics/             # TacticalMap, Unit, TacticalCommand, LineOfSight, Pathing, AlienAi
│       ├── mission/             # Mission, MissionFactory, MissionOutcome
│       └── game/                # GameState, GameCommand, GameEvent, GameEngine, StateDigest
├── data/                        # kotlin("jvm") + kotlinx.serialization. domain'e bağımlı.
│   └── src/main/kotlin/hypogeum/data/
│       ├── serialization/       # JsonConfig, SaveEnvelope, Migrations
│       ├── sync/                # GameSync (interface), SyncMessage, LockstepSession, LocalHotseatSync
│       └── repository/          # MatchRepository (yerel kayıt)
│       # FirebaseSync ayrı bir modülde (:sync-firebase) tutulur; Android bağımlılığı olduğu için
└── app/                         # Android application. ui paketi burada.
    └── src/main/kotlin/hypogeum/ui/
        ├── app/                 # Activity, Navigation, manuel DI (Hilt yok, MVP'de gereksiz)
        ├── campus/              # CampusScreen, FacilityGrid, RosterScreen, ResearchScreen
        ├── world/               # WorldMapScreen, RegionSheet
        ├── tactics/             # TacticalScreen, GridCanvas, UnitPanel
        ├── session/             # GameSessionViewModel: StateFlow<GameState> ve olay akışı
        └── theme/
```

**Bağımlılık kuralı:** `ui → data → domain`. `domain` hiçbir şeye bağlı değildir (yalnızca `kotlinx.serialization` annotation'ları ve `kotlin.math`'ı kullanabilir; ikisi de platform bağımsızdır). Compose tarafı domain'in hiçbir tipini değiştirmez; yalnızca okur ve komut üretir.

**Tek kaynak kuralı:** Oyun kuralı UI'a sızmaz. Bir tesisin inşa edilip edilemeyeceği, bir hamlenin geçerli olup olmadığı yalnızca `domain` tarafından söylenir. UI, `GameEngine.preview(state, command)` ile spekülatif kopya üzerinde önizleme gösterir.

---

## 2. Veri modeli

### 2.1 Çekirdek tipler

```kotlin
// core
@Serializable data class Pos(val x: Int, val y: Int)              // yalnızca Int; ızgara tabanlı (value class değil: Kotlin tek alan kısıtı)
@Serializable enum class PlayerId { P1, P2 }
@Serializable data class PerPlayer<T>(val p1: T, val p2: T)           // Map yerine: sabit sıra, sabit alan
@Serializable data class RngState(val s: Long)                        // SplitMix64 durumu
@Serializable data class IdCounter(val next: Int)                     // ID = "soldier-0042" gibi; UUID YOK
@Serializable value class RegionId(val raw: String)
@Serializable value class TechId(val raw: String)
```

### 2.2 Dünya (paylaşılan, iki oyuncu için ortak)

```kotlin
@Serializable data class WorldState(
    val regions: List<Region>,              // sabit sıra; content'ten gelir, sıra değişmez
    val incursions: List<Incursion>,        // id'ye göre sıralı tutulur
    val globalAlert: Int,                   // 0..100; uzaylı gücünü ölçekler
    val transit: TransitLink,               // iki kampüs arası hat
)

@Serializable data class Region(
    val id: RegionId,
    val name: String,
    val neighbors: List<RegionId>,
    val threat: Int,                        // 0..100
    val claimedBy: PlayerId?,               // null = sahipsiz
    val nodes: List<ResourceNode>,          // sabit sıra
    val mapTemplate: String,                // taktik harita şablonu id'si
)

@Serializable data class ResourceNode(
    val id: String,
    val kind: ResourceKind,                 // ALLOY | CELLS | BIOMASS
    val yieldPerEpoch: Int,
    val claimedBy: PlayerId?,
)

@Serializable data class Incursion(
    val id: String,
    val regionId: RegionId,
    val strength: Int,
    val targetCampus: PlayerId?,            // null: bölgeye saldırır; dolu: kampüse yönelir
    val arrivalEpoch: Int,                  // uyarı: bir epoch önce görünür, savaş bir sonraki Tactical fazında olur
)

@Serializable data class TransitLink(
    val transitEpochs: Int,                 // kargo varış gecikmesi
    val capacityPerEpoch: Int,              // paylaşılan kapasite; çakışma kuralı 5. bölümde
    val usedThisEpoch: Int,                 // Resolution sırasında güncellenir, Upkeep'te sıfırlanır
)
```

### 2.3 Kampüs (oyuncuya özel)

```kotlin
@Serializable data class Campus(
    val owner: PlayerId,
    val status: CampusStatus,               // OPERATIONAL | DEGRADED | ISOLATED | LOST
    val grid: List<FacilitySlot>,           // 6x4 = 24 slot, index sabit
    val stock: Resources,                   // özel; diğer oyuncu projection'da görmez
    val roster: List<Soldier>,              // id'ye göre sıralı
    val research: ResearchState,
    val queue: List<ProductionOrder>,       // inşa, eğitim, araştırma, onarım sırasıyla
)

@Serializable enum class CampusStatus { OPERATIONAL, DEGRADED, ISOLATED, LOST }

@Serializable data class FacilitySlot(val index: Int, val facility: Facility?)

@Serializable data class Facility(
    val id: String,
    val type: FacilityType,
    val level: Int,
    val hp: Int,
)

@Serializable enum class FacilityType {
    REACTOR,        // cells üretir
    FOUNDRY,        // alloy üretir
    HYDROPONICS,    // biomass üretir
    BARRACKS,       // aynı anda eğitilen asker kapasitesi
    LAB,            // araştırma hızı
    RELAY,          // transfer açar, dünya istihbaratı verir; yoksa kampüs ISOLATED olur
    INFIRMARY,      // yaralı iyileşme hızı
    HANGAR,         // mission'a asker sevk kapasitesi
    BULWARK,        // savunma mission'ında taktik bonus
}

@Serializable data class Resources(val alloy: Int, val cells: Int, val biomass: Int)

@Serializable data class ResearchState(
    val completed: List<TechId>,            // sıralı
    val active: ActiveResearch?,
)
@Serializable data class ActiveResearch(val tech: TechId, val remainingEpochs: Int)

@Serializable sealed interface ProductionOrder {
    val id: String
    val remainingEpochs: Int
    @Serializable data class Build(override val id: String, override val remainingEpochs: Int,
                                   val slotIndex: Int, val type: FacilityType) : ProductionOrder
    @Serializable data class Train(override val id: String, override val remainingEpochs: Int,
                                   val soldier: Soldier) : ProductionOrder
    @Serializable data class Repair(override val id: String, override val remainingEpochs: Int,
                                    val facilityId: String) : ProductionOrder
}
```

Not: `ResearchState` ayrı bir kuyruk gibi görünse de tek aktif araştırma tutuyoruz; kuyruk ve paralel araştırma MVP dışı. `queue` içindeki `Research` emri yerine `research.active` kullanılır.

### 2.4 Asker

```kotlin
@Serializable data class Soldier(
    val id: String,
    val name: String,                       // content'ten, RNG ile seçilmiş
    val cls: SoldierClass,                  // RIFLEMAN | HEAVY | MEDIC | ENGINEER | SCOUT
    val level: Int,
    val xp: Int,
    val stats: Stats,                       // maxHp, aim, mobility, will
    val hp: Int,
    val injuryEpochs: Int,                  // 0 = sağlıklı
    val loadout: Loadout,
)

@Serializable data class Stats(val maxHp: Int, val aim: Int, val mobility: Int, val will: Int)
@Serializable data class Loadout(val weaponId: String, val armorId: String, val gadgetId: String?)
```

Ölüm asker nesnesinin silinmesidir; "dead" bir durum olarak tutulmaz. Yaralı askerler kampüs roster'ında kalır, `injuryEpochs > 0` ise dispatch edilemez.

### 2.5 Transfer

```kotlin
@Serializable data class Transfer(
    val id: String,
    val from: PlayerId,
    val to: PlayerId,
    val resources: Resources,
    val soldiers: List<Soldier>,            // dispatch anında roster'dan çıkarılır
    val arrivalEpoch: Int,
)
```

Transfer maliyeti üç katmanlıdır: (a) gönderilen kaynağın %20'si "taşıma kaybı" olarak yanar; (b) `transit.transitEpochs` kadar gecikir; (c) `RELAY` tesisi olmadan başlatılamaz. Kapasite `TransitLink.capacityPerEpoch` ile sınırlıdır.

### 2.6 Mission (taktik)

```kotlin
@Serializable data class Mission(
    val id: String,
    val kind: MissionKind,                  // CONTESTED_REGION | CAMPUS_DEFENSE
    val regionId: RegionId,
    val participants: List<PlayerId>,       // 1 veya 2; 2 ise co-op
    val map: TacticalMap,
    val units: List<Unit>,                  // id'ye göre sıralı
    val round: Int,
    val activeSide: TacticalSide,           // P1 | P2 | ALIENS
    val outcome: MissionOutcome?,
)

@Serializable enum class MissionKind { CONTESTED_REGION, CAMPUS_DEFENSE }
@Serializable enum class TacticalSide { P1, P2, ALIENS }

@Serializable data class TacticalMap(val width: Int, val height: Int, val tiles: List<Tile>)  // row-major
@Serializable data class Tile(val terrain: Terrain, val elevation: Int)
@Serializable enum class Terrain { FLOOR, RUBBLE, WALL, LOW_COVER, HIGH_COVER, WATER }

@Serializable data class Unit(
    val id: String,
    val side: UnitSide,                     // PLAYER(owner) | ALIEN
    val soldierId: String?,                 // PLAYER için
    val alienType: AlienType?,              // ALIEN için
    val pos: Pos,
    val hp: Int,
    val maxHp: Int,
    val ap: Int,                            // kalan aksiyon puanı; her faz başında yenilenir
    val stats: Stats,
    val overwatch: Boolean,
    val statuses: List<UnitStatus>,         // sıralı
)

@Serializable sealed interface UnitSide {
    @Serializable data class Player(val owner: PlayerId) : UnitSide
    @Serializable data object Alien : UnitSide
}

@Serializable enum class AlienType { CRAWLER, STALKER, SPITTER, BRUTE, SPAWNER }
@Serializable enum class UnitStatus { SUPPRESSED, BURNING, STUNNED }

@Serializable data class MissionOutcome(val victory: Boolean, val survivors: List<String>, val lostSoldiers: List<String>)
```

### 2.7 Oyun durumu ve faz

```kotlin
@Serializable data class GameState(
    val schemaVersion: Int,
    val engineVersion: String,              // denge değişince artar; saves ve handshake buna bağlı
    val matchId: String,
    val seed: Long,
    val rng: RngState,
    val ids: IdCounter,
    val epoch: Int,
    val phase: GamePhase,
    val world: WorldState,
    val campuses: PerPlayer<Campus>,
    val transfers: List<Transfer>,          // arrivalEpoch'a göre sıralı
    val pendingCommits: PerPlayer<CommitBatch?>,
    val mission: Mission?,                  // MVP: epoch başına en fazla bir aktif mission
    val lastReport: EpochReport?,           // UI için son epoch özeti; sync digest'e dahil
    val gameOver: GameResult?,
)

@Serializable sealed interface GamePhase {
    @Serializable data object Planning : GamePhase
    @Serializable data object Resolving : GamePhase      // yalnızca iki commit de gelince; anlık geçiş
    @Serializable data class Tactical(val missionId: String) : GamePhase
    @Serializable data object Aftermath : GamePhase
}

@Serializable data class CommitBatch(
    val epoch: Int,
    val actor: PlayerId,
    val commands: List<StrategicCommand>,   // gönderim sırası = uygulama sırası (kanonik sıralama bunu yeniden düzenler)
    val draftDigest: String,                // kendi state'inin hash'i; karşı taraf doğrular
)

@Serializable data class EpochReport(val epoch: Int, val events: List<GameEvent>)
@Serializable data class GameResult(val won: Boolean, val reason: String)
```

**Neden `GameState` tek ağaç?** Save/load, digest ve replay için tek bir kanonik serileştirme noktası gerekir. Parçalara bölünmüş state'lerin (örn. ayrı world/campus repository'leri) birbirinden senkron kayması, tam da bu oyunun çözmeye çalıştığı problemin küçük bir kopyası olur.

### 2.8 Komutlar ve olaylar

```kotlin
// Strateji: Planning fazında sadece draft'a eklenir, Resolution'da uygulanır.
@Serializable sealed interface StrategicCommand {
    val actor: PlayerId
    @Serializable data class BuildFacility(override val actor: PlayerId, val slotIndex: Int, val type: FacilityType) : StrategicCommand
    @Serializable data class DemolishFacility(override val actor: PlayerId, val slotIndex: Int) : StrategicCommand
    @Serializable data class StartResearch(override val actor: PlayerId, val tech: TechId) : StrategicCommand
    @Serializable data class Recruit(override val actor: PlayerId, val cls: SoldierClass) : StrategicCommand
    @Serializable data class Dispatch(override val actor: PlayerId, val regionId: RegionId, val soldierIds: List<String>) : StrategicCommand        // PAYLAŞILAN aksiyon
    @Serializable data class ClaimNode(override val actor: PlayerId, val nodeId: String) : StrategicCommand                                       // PAYLAŞILAN aksiyon
    @Serializable data class SendTransfer(override val actor: PlayerId, val resources: Resources, val soldierIds: List<String>) : StrategicCommand // PAYLAŞILAN kapasite
}

// Taktik: yalnızca fazın sahibi gönderir; kabul edilenler log'a yazılır.
@Serializable sealed interface TacticalCommand {
    val actor: PlayerId
    @Serializable data class MoveUnit(override val actor: PlayerId, val unitId: String, val to: Pos) : TacticalCommand   // yol reducer'da hesaplanır
    @Serializable data class Shoot(override val actor: PlayerId, val unitId: String, val targetId: String) : TacticalCommand
    @Serializable data class SetOverwatch(override val actor: PlayerId, val unitId: String) : TacticalCommand
    @Serializable data class UseAbility(override val actor: PlayerId, val unitId: String, val abilityId: String, val target: Pos) : TacticalCommand
    @Serializable data class EndPhase(override val actor: PlayerId) : TacticalCommand
}

@Serializable sealed interface GameCommand {
    @Serializable data class Strategic(val cmd: StrategicCommand) : GameCommand
    @Serializable data class Tactical(val cmd: TacticalCommand) : GameCommand
    @Serializable data class Commit(val batch: CommitBatch) : GameCommand
}

// Olaylar: UI animasyonu ve log içindir. Sync digest'ine GİRMEZ; state'ten türetilebilir olmalıdır.
@Serializable sealed interface GameEvent {
    @Serializable data class CommandRejected(val actor: PlayerId, val reason: String) : GameEvent
    @Serializable data class FacilityBuilt(val campus: PlayerId, val slotIndex: Int) : GameEvent
    @Serializable data class SoldierRecruited(val campus: PlayerId, val soldierId: String) : GameEvent
    @Serializable data class RegionClaimed(val regionId: RegionId, val by: PlayerId) : GameEvent
    @Serializable data class IncursionMoved(val incursionId: String, val to: RegionId) : GameEvent
    @Serializable data class ShotFired(val attackerId: String, val targetId: String, val hit: Boolean, val damage: Int) : GameEvent
    @Serializable data class UnitDied(val unitId: String) : GameEvent
    @Serializable data class MissionEnded(val missionId: String, val victory: Boolean) : GameEvent
    @Serializable data class TransferArrived(val transferId: String, val to: PlayerId) : GameEvent
    // ... (tam liste implementasyonda genişler; her yeni event bir UI karşılığı gerektirir)
}
```

**Kural:** `GameEvent` hiçbir zaman state'in tek kaynağı değildir. Bir event'i yeniden üretemiyorsan, o bilgi state'e yazılmalıdır.

### 2.9 İçerik (content)

Denge verisi (tesis maliyetleri, uzaylı statları, araştırma ağacı) **Kotlin objesi** olarak `domain/content` içinde tutulur, JSON'da değil. Gerekçe: derleyici kontrolü, `engineVersion` ile sürümleme ve JSON parse hatasının determinizmi bozma riskinin olmaması. Tasarımcılar için ileride JSON'a export edilebilir, ama kaynak doğru content kodu olur.

---

## 3. Tur sistemi: stratejik ve taktik katmanların sırası

### 3.1 Kavramlar

- **Epoch:** stratejik gün. Her oyun turu bir epoch'tur.
- **Round:** taktik misyonda bir tam dönüş (P1 fazı → P2 fazı → ALIENS fazı).
- **Faz sahibi:** taktikte bir fazın tek aktif aktörü. Komutları yalnızca o gönderir.

### 3.2 Epoch pipeline (kanonik sıra)

Her adım önceki adımın çıktısını girdi olarak alır. Adımlar arası hiçbir yerde "paralel" işlem yoktur.

```
EPOCH n
 ├─ 1. UPKEEP            (girdi yok)
 │      üretim kuyrukları ilerler, araştırma ilerler, transfer'ler varışa göre teslim,
 │      yaralılar iyileşir, TransitLink.usedThisEpoch = 0, kaynak üretimi
 │
 ├─ 2. PLANNING          (girdi: her oyuncunun lokal draft'ı; sunucu/peer'a gitmez)
 │      Draft, state'in spekülatif kopyasına uygulanır → UI önizlemesi.
 │      Draft'ta kaynak "rezerve" edilmez; yalnızca önizleme.
 │
 ├─ 3. COMMIT            (girdi: iki CommitBatch)
 │      Her iki taraf commit gönderir. İkisi de gelene kadar Resolution başlamaz.
 │      Commit'te kendi state digest'i de yer alır; karşı taraf doğrular.
 │
 ├─ 4. RESOLUTION        (girdi: iki batch; deterministik sıra, aşağıda)
 │      4a. Paylaşılan aksiyonlar (ClaimNode, Dispatch, SendTransfer-kapasitesi)
 │      4b. Kampüse özel aksiyonlar (P1 sırası, sonra P2) — kendi kaynağını harcar
 │      4c. Dispatch edilmiş askerlerden mission oluşturma
 │
 ├─ 5. TACTICAL          (yalnızca mission varsa; yoksa atlanır)
 │      Round döngüsü: P1 fazı → P2 fazı → ALIENS fazı, misyon bitene kadar.
 │
 ├─ 6. AFTERMATH         (girdi: mission sonucu)
 │      Ölüm, yaralanma, XP, ganimet, savunma başarısızlığında tesis hasarı,
 │      bölge sahipliği ve threat güncellemesi.
 │
 ├─ 7. WORLD (alien turn) (girdi: yok, tamamen seed'den)
 │      Incursion hareketi, yeni incursion spawn, threat artışı, globalAlert.
 │      Kampüse yönelen incursion → epoch n+1'de CAMPUS_DEFENSE mission'ı.
 │
 └─ 8. EPOCH n+1 → PLANNING
```

**Neden taktik, aftermath'ten önce?** Misyon sonucu, dünyanın kalan adımlarının girdisidir. Bir savunma misyonu kaybedilirse incursion aynı epoch içinde kampüsü ezmemeli; uyarı-savaş-sonuç ayrı epoch'lara dağılmalıdır. Bu, oyuncuya planlama penceresi bırakır.

**Neden incursion bir epoch gecikmeli?** İki oyuncunun da tepki verebilmesi için. Anlık savaş, co-op'ta "bir oyuncu bunu görmeden kaybetti" hissi üretir. Bu bir tasarım seçimidir; bunu değiştirmek tek satırlık bir `arrivalEpoch` farkıdır.

### 3.3 Taktik round

```
ROUND r
 ├─ P1 FAZI   (eğer P1 katılımcıysa; değilse atlanır)
 │    Sahibi MoveUnit / Shoot / SetOverwatch / UseAbility gönderir.
 │    EndPhase veya tüm birimlerin AP'si biterse faz kapanır.
 ├─ P2 FAZI   (aynı kural)
 ├─ ALIENS FAZI
 │    Oyuncu girdisi yok. AlienAi, birimleri id sırasıyla karar verir.
 │    Her karar bir tam komut olarak log'a yazılır (determinizm için).
 └─ Zafer/yenilgi kontrolü; değilse round + 1
```

**Faz içi determinizm:** `MoveUnit` yolunu reducer hesaplar (BFS, komşu sırası sabit: N, E, S, W). Aynı `to` hedefi her cihazda aynı yolu verir.

**Atış:** `hit = roll(0..99) < hitChance`; `hitChance` integer formülüdür. Roll, RNG'den o anki sırada çekilir. Atış yapmayan komutlar RNG tüketmez — bu, "kim neyi tükettiğini" değiştirmez, çünkü tüketim sırası komutun kendi uygulanma sırasıyla belirlenir.

### 3.4 Tek oyunculu, co-op ve hotseat'in aynı protokolü kullanması

Tek oyunculu "bot" modu, hotseat ve online co-op aynı pipeline'ı koşar. Fark yalnızca `GameSync` implementasyonundadır:

- `LocalHotseatSync`: iki "koltuk" aynı cihazda, aktör sırası UI'da değişir.
- `FirebaseSync`: iki cihaz, mesaj aktarımı Firestore üzerinden.

Hotseat'i ayrı bir kod yolu yapmak, zamanla iki yolun farklı davranmasına ve tam da bu yüzden desync'e yol açar.

---

## 4. Co-op senkronizasyon modeli

### 4.1 Model: sıralı komut logu ile lockstep

Her iki cihaz aynı şeyi tutar:

```
log = [ (seq, GameCommand) ... ]     // yalnızca KABUL EDİLMİŞ komutlar
state = fold(apply, initialState(seed, setup), log)
```

- `seq` maç içinde monoton artan bir tamsayıdır. Gönderen ve alan aynı `seq`'i kullanır.
- Her iki taraf da bir komutu uygulamadan önce `seq == lastSeq + 1` kontrolü yapar. Boşluk varsa bekler, tekrar varsa atlar (idempotent).
- Reddedilen komut log'a girmez. Reddedilme deterministik olduğu için her iki taraf da aynı komutu aynı şekilde reddeder; gereksiz trafik üretilmez.

### 4.2 Strateji: gizli planlama, açık commit

```
Planning (lokal, gönderilmez)
   ↓ oyuncu "Commit"e basar
CommitBatch(epoch, actor, commands, draftDigest) → peer'a gider
   ↓ iki CommitBatch da geldi mi?
Resolution (her iki cihazda aynı kanonik sırayla)
   ↓
Post-resolution digest karşılaştırması
```

**Neden draft gönderilmiyor?** Planlama aşamasında rakibin hamlesini görmek co-op'ta bile stratejiyi bozar (birbirini kopyalama, "onlar şunu yapacaksa biz bunu"). Draft yalnızca commit'te açılır.

**Desync kontrolü iki noktada:**
1. Commit'te: `draftDigest` karşı tarafın kendi projeksiyonuyla eşleşmez → uyumsuzluk (genelde protokol/sürüm farkı).
2. Resolution sonrası: her iki cihaz `StateDigest(state)` hesaplar ve paylaşır. Eşleşmezse → desync.

### 4.3 Taktik: faz sahipliği

- Her fazın bir sahibi vardır. Sahip komutu **doğrular, uygular ve gönderir**; karşı taraf **alır ve uygular**.
- Karşı taraf kendi lokal komut üretemez; bu, iki cihazın aynı anda iki farklı hamle üretmesini yapısal olarak engeller.
- Her faz sonunda (`EndPhase` uygulandıktan sonra) digest karşılaştırılır.
- Ölçek: her komut gönderilir ama yalnızca kabul edilenler; reddedilen komut sahibinin kendi UI'ında kalır.

### 4.4 Çakışma çözüm tablosu

| Çakışma | Kural | Gerekçe |
|---|---|---|
| İki oyuncu aynı `ResourceNode`'u talep eder | Epoch başına bir kez seed'den **kanonik sıra** çekilir (coin flip). Kazanan talebi kaynağı harcanarak uygulanır; kaybeden `CommandRejected(NodeTaken)` alır ve **hiçbir kaynak harcanmamıştır**. | "P1 her zaman kazanır" adil değil; seed herkese açık olduğu için tahmin edilebilirlik bir sorun değil (co-op, rekabetçi değil). |
| `TransitLink` kapasitesi aşılır | Aynı kanonik sıra ile. Kapasiteyi aşan kısım reddedilir. | Aynı mekanizma; tek bir "paylaşılan kaynak sırası" kuralı. |
| İki oyuncu aynı bölgeye `Dispatch` eder | **Çakışma değil, birleşme.** Tek bir co-op mission oluşur, `participants = [P1, P2]`. | Co-op oyunun ana vaadi. Tek mission'a iki squad girer. |
| İki oyuncu farklı bölgelere dispatch eder (MVP) | Mission'lar `regionId` sırasıyla **sırayla** oynanır. | MVP'de epoch başına bir aktif mission; paralel mission v2. |
| Aynı asker iki kez dispatch edilir | Olamaz: asker sahibine ait. Eğer komut bozuk gelirse, ikinci komut `NotOwned/AlreadyDispatched` ile reddedilir. | Sahiplik invariant'ı. |
| Taktikte iki oyuncu aynı birimi oynatmaya çalışır | Olamaz: faz sahipliği. | Yapısal. |
| Bağlantı koparsa (faz ortasında) | Oyun **duraklar**; otomatik forfeit yok (MVP). Yeniden bağlanınca log'un son `seq`'inden devam. | Açık soru, §7. |
| Digest uyuşmazlığı | **Dur.** Oyun ilerlemez, iki taraf "desync" durumuna geçer; `engineVersion` ve `schemaVersion` karşılaştırılır. Otomatik düzeltme yok (§4.6). | Deterministik motorda uyuşmazlık bir hata sinyalidir; üstünü örtmek daha kötü. |

### 4.5 Protokol mesajları (GameSync arayüzü)

```kotlin
interface GameSync {
    val incoming: Flow<SyncMessage>
    suspend fun send(message: SyncMessage)
    suspend fun close()
}

@Serializable sealed interface SyncMessage {
    @Serializable data class Hello(val matchId: String, val actor: PlayerId,
                                   val engineVersion: String, val schemaVersion: Int) : SyncMessage
    @Serializable data class Command(val seq: Int, val command: GameCommand) : SyncMessage  // taktik ve tek komut
    @Serializable data class Ack(val upToSeq: Int) : SyncMessage
    @Serializable data class Digest(val seq: Int, val hash: String) : SyncMessage
    @Serializable data class ResyncRequest(val fromSeq: Int) : SyncMessage
    @Serializable data class Snapshot(val state: GameState, val atSeq: Int) : SyncMessage
}
```

- `GameSync` yalnızca taşır; yorumlamaz. Yorumlama `LockstepSession`'da (`data` katmanı) olur.
- `LocalHotseatSync` bir `Channel`'dır. `FirebaseSync` Firestore'da `matches/{id}/log/{seq}` ve `matches/{id}/digests/{seq}` koleksiyonlarını kullanır. İkisi de aynı `SyncMessage` sözleşmesine uyar.
- `Hello` handshake'te `engineVersion` veya `schemaVersion` uyuşmazsa bağlantı reddedilir. Denge değişince `engineVersion` artar; eski kayıtlar yüklenemez veya migration'dan geçer.

### 4.6 Desync ve kurtarma

Deterministik motorda uyuşmazlık **bir hata sinyalidir**, normal bir durum değil. Dolayısıyla:

1. İlk versiyonda: desync → oyun durur, her iki tarafta "Senkron hatası" gösterilir, log ve iki digest dışa aktarılabilir (bug raporu için).
2. Sonraki versiyonda (M6): `ResyncRequest` ile bir taraf kendi snapshot'ını gönderir. Hangi snapshot'ın doğru olduğu **kararlaştırılmış bir otorite** ile belirlenir (maçı oluşturan oyuncu = host). Host'un state'i referanstır. Bu, "kim haklı" sorusunu bir kurala bağlar; sessizce kazananı seçmez.

### 4.7 Kayıt formatı

```kotlin
@Serializable data class SaveEnvelope(
    val schemaVersion: Int,
    val engineVersion: String,
    val snapshot: GameState,
    val logSinceSnapshot: List<LoggedCommand>,   // opsiyonel; replay ve debug için
)
```

- Kayıt, `snapshot + log` olarak tutulabilir. Log tutulursa kayıt yeniden oynatılabilir (replay). Bu, "bu durum nasıl oluştu" sorusunu cevaplamanın en ucuz yoludur.
- Migration: `schemaVersion` artınca `Migrations` listesi sırayla çalışır. Her migration saf bir fonksiyon ve test edilebilir.
- JSON konfigürasyonu tek yerde: `encodeDefaults = true`, `explicitNulls = true`, `classDiscriminator = "t"`, `ignoreUnknownKeys = false`. Bu, aynı state'in iki cihazda aynı byte'larla serileşmesini garanti eder — digest'in dayandığı şey budur.

---

## 5. Determinizm kuralları (ihlali bir hata sayılır)

Bu bölüm kod incelemesinde kontrol listesi olarak kullanılmalıdır.

1. **RNG:** Tek tip. `SplitMix64` (kendi implementasyonumuz; `kotlin.random.Random` ve `java.util.Random` kullanılmaz çünkü platform garantisi vermeyebilir). Durum `GameState.rng` içinde; her çekim durumu ilerletir.
2. **Çekim sırası kuraldır:** Bir fonksiyon RNG'yi yalnızca kendi içinde, sabit bir sırada çeker. Koşullu çekim varsa koşul da deterministik olmalıdır.
3. **Floating point yok.** `Float`, `Double` domain'de yasaktır. Oranlar yüzde (`Int`) veya binde olarak tutulur. Ondalık gerekirse `Int` ölçekli fixed-point.
4. **Koleksiyonlar:** `Map` ve `Set` state'te yasaktır. `List` sıralı tutulur (id veya index). `HashMap` iterasyonu asla domain'de kullanılmaz.
5. **ID üretimi:** `GameState.ids` sayaç ile. UUID, `System.nanoTime()`, `hashCode()` (JVM'de hash, platform bağımlı olabilir) yasak.
6. **Zaman:** Domain'de saat yok. Zaman epoch ve round sayılarıyla ifade edilir.
7. **Yan etki yok:** `apply` ve `resolveEpoch` saf fonksiyondur. Logging, I/O, coroutine domain'de olmaz.
8. **Kanonik sıra:** Paylaşılan çözümlemelerde sıra, seed'den çekilen coin flip + slot index ile belirlenir. Gönderim sırası (ağ gecikmesi) hiçbir zaman sonucu belirlemez.

---

## 6. Test stratejisi

| Test türü | Ne doğrular | Nerede |
|---|---|---|
| Golden RNG | Belirli seed için ilk N çekim sabit değerler | `:domain` testleri |
| Golden digest | Belirli bir başlangıç state'inin hash'i sabit | `:domain` testleri (sürüm değişince bilinçli güncellenir) |
| Serileştirme round-trip | `encode(decode(x)) == x` ve byte-level eşitlik | `:data` |
| Reducer totality | Rastgele ama geçerli-ya-da-reddedilen komutlarda exception yok | `:domain` property test |
| İnvariantlar | Kaynaklar ≥ 0, roster kapasitesi aşılmaz, id'ler benzersiz, grid index'leri geçerli | `:domain` her adımdan sonra |
| Replay | Aynı seed + log → aynı final digest (1000+ rastgele simülasyon) | `:domain` |
| Cihazlar arası | Aynı log JVM'de ve Android (ART) üzerinde aynı digest'i üretir | CI'da instrumented test |
| Senkron protokol | Duplicate, eksik, sıra dışı mesaj; corrupted digest → desync | `:data`, fake `GameSync` |
| Firebase | Gerçek emulator'da iki istemci; disconnect/reconnect | M6 |

---

## 7. Açık sorular (varsayılanla ilerliyorum, değiştirilebilir)

| # | Soru | Varsayılan karar | Neden önemli |
|---|---|---|---|
| Q1 | Hamle zamanlayıcısı var mı? | **Yok (MVP).** Bağlantı kopması = duraklama. | Zamanlayıcı, forfeit kuralı ister; ayrı bir tasarım konusu. |
| Q2 | Gizli bilgi (fog) hangi düzeyde? | **Güvenlik değil, UX.** Peer'in kampüs stoku UI'da gizlenir; state yine de iki cihazda tam. | Co-op'ta ağ tarafı zaten her şeyi görür; hile önleme hedef değil. |
| Q3 | Paralel mission? | **v2.** MVP'de epoch başına bir aktif mission. | Taktik sahipliği paralel mission ile karmaşıklaşır. |
| Q4 | Harita üretimi | Seed'li **şablon + varyasyon**, tamamen deterministik. | Procedural'ın determinizmi kolay ama test yükü yüksek; şablonla başlarız. |
| Q5 | Tek oyunculu bot | **Yok.** Tek oyuncu hotseat ile iki koltuğu da kendi oynar. | Bot, aynı protokolde ikinci bir karar kaynağı ekler. |
| Q6 | Metin dili | Arayüz Türkçe, id'ler İngilizce. | — |

---

## 8. Geliştirme kilometre taşları

Her milestone tek başına çalışır, kendi testleri vardır ve bir öncekine bağımlıdır ama sonrakine bağımlı değildir. Sıra "en küçük oynanabilir" ilkesine göre: önce taktik çekirdek.

### M0 — Temel (oynanabilir değil)
- **Kapsam:** Gradle modülleri (`:domain`, `:data`, `:app`), `DetRng` (SplitMix64), `Pos`, `PerPlayer`, kanonik JSON config, `StateDigest`, boş `GameState`.
- **Tamamlanma:** Golden RNG ve golden digest testleri yeşil; `:domain` Android plugin'i içermiyor (build'de kanıtlanır); serileştirme round-trip testi geçiyor.
- **Neden önce:** Determinizm bozulursa sonraki her şey yanlış olur. İlk gün test edilebilir kılınır.

### M1 — Taktik Skirmish (ilk oynanabilir sürüm, tek cihaz)
- **Kapsam:** 8×8 sabit harita, 3 asker vs 3 uzaylı (tek tip: CRAWLER). `MoveUnit`, `Shoot`, `EndPhase`. LOS (Bresenham, integer), cover, BFS yol bulma, `AlienAi` (basit: en yakın hedefe yürü ve vur). Zafer/yenilgi. Tek cihazda iki oyuncu sırayla (hotseat'in ilk hali: `P1` ve `P2` fazı, ama aynı squad'ı P1 yönetir — veya sadece P1 varsayılır).
- **UI:** Grid çizimi, birim seçimi, aksiyon butonları, faz göstergesi.
- **Tamamlanma:** Bir mission baştan sona oynanabilir; aynı seed + komut logu → aynı final digest (replay testi); property testi 1000 simülasyonda invariant ihlali yok.
- **Neden ilk:** Oyunun en ayırt edici kısmı taktik. Strateji olmadan bile eğlenceli mi, sorusu en erken burada cevaplanır.

### M2 — Tek kampüs stratejisi
- **Kapsam:** 6×4 grid, `BuildFacility` (süreli), `Recruit`, üretim (`REACTOR`, `FOUNDRY`, `BARRACKS`), `Planning → Commit → Resolution` (tek oyuncu, ama protokol tam), tek bölgeye `Dispatch`, M1'in mission'ını tetikler, `Aftermath` (ölüm, yaralanma, XP).
- **Tamamlanma:** Döngü: inşa → asker → dispatch → mission → dönüş → yeni epoch. Her epoch sonunda digest üretilir ve doğrulanır (tek cihazda kendini doğrular — bu zayıf ama kapı açar).
- **Test:** Kaynak yetersizse inşa reddedilir; yaralı asker dispatch edilemez; ölen asker roster'dan silinir.

### M3 — İki kampüs, paylaşılan dünya, LocalHotseatSync
- **Kapsam:** P2 kampüsü, `WorldState` (bölgeler, threat, `ClaimNode`), incursion uyarısı (bir epoch önce), `LocalHotseatSync`, `CommitBatch` ile gerçek iki-taraflı commit, kanonik sıra (coin flip ile node çakışması).
- **Tamamlanma:** İki koltuk aynı cihazda bir epoch'u tamamlar; digest iki taraf için eşleşir; node çakışma testi: kaybeden `NodeTaken` alır ve kaynağı harcanmaz.
- **Hedef:** Co-op'un iskeleti burada hazır olur; ağ hariç.

### M4 — Co-op taktik
- **Kapsam:** Mission `participants = [P1, P2]`, faz sahipliği ile sırayla P1 → P2 → ALIENS, aynı bölgeye iki dispatch'in birleşmesi, faz bazlı UI (yalnızca kendi fazında etkileşim).
- **Tamamlanma:** İki squad'lı bir mission hotseat'te oynanır; her faz sonu digest eşleşir; yalnızca fazın sahibi komut üretebilir (test).

### M5 — Ekonomi derinliği: transfer, araştırma, yaralanma, savunma
- **Kapsam:** `SendTransfer` (%20 kayıp, gecikme, RELAY şartı, kapasite), `StartResearch` (tek aktif), yaralanma iyileşmesi (`INFIRMARY`), savunmada tesis hasarı, kampüs `DEGRADED/ISOLATED` durumları.
- **Tamamlanma:** Transfer maliyeti ve gecikmesi testle doğrulanır; kapasite aşımı reddedilir; RELAY olmayan kampüs transfer başlatamaz; ISOLATED kampüs threat'i etkilemez ama kendi üretimi düşer (ya da tersi — denge kararı M7'de).

### M6 — Ağ: FirebaseSync
- **Kapsam:** `GameSync` Firestore implementasyonu, `Hello` handshake (sürüm kontrolü), log ve digest koleksiyonları, reconnect (`ResyncRequest` + log tail), desync tespiti ve durdurma.
- **Tamamlanma:** Emulator'da iki istemci bir epoch oynar; bir istemci koparılıp yeniden bağlanır ve devam eder; corrupted digest enjekte edilince desync'e girer ve ilerlemez; sürüm uyuşmazlığında handshake reddedilir.
- **Not:** Snapshot-based kurtarma (§4.6 madde 2) bu milestone'da değil, M7'de; önce tespit, sonra düzeltme.

### M7 — İçerik, denge, cila
- **Kapsam:** Araştırma ağacının tamamı, tüm asker sınıfları, uzaylı çeşitliliği, kampanya ilerlemesi (zafer/yenilgi koşulları), kayıt/yükleme UI'ı, migration altyapısının ilk gerçek kullanımı, snapshot-based resync.
- **Tamamlanma:** Denge tablosu (bir sayfa) var; cihazlar arası determinizm testi (JVM vs ART) CI'da geçiyor; kayıt eski `schemaVersion` ile açılıyor.

---

## 9. Riskler

| Risk | Olasılık | Etki | Önlem |
|---|---|---|---|
| Determinizmin sessizce bozulması (ör. bir `HashMap` iterasyonu) | Orta | Yüksek | Golden digest + replay testi her commit'te; `Map`/`Set` yasağı |
| JVM ↔ ART farkı | Düşük (Int/Long ile) | Yüksek | Float yasağı; CI'da iki platform digest karşılaştırması (M7'den önce bile, M3'te yerel) |
| Strateji ve taktik ayrı kod yollarına bölünür | Orta | Orta | Tek pipeline; hotseat'i ayrı yol yapmama kuralı |
| Denge değişince eski kayıt/co-op maçı bozulur | Yüksek | Orta | `engineVersion` handshake ve save; maç başında versiyon kilidi |
| Commit'te digest uyumsuzluğu yanlış pozitif | Düşük | Orta | `draftDigest` yalnızca `Planning` sonunda hesaplanır; test |
| Kapsam şişmesi (M1'de strateji eklemek) | Yüksek | Yüksek | Milestone DoD'leri; M1 yalnızca taktik |

---

## 10. Bu dokümanın dışında kalanlar (bilinçli)

- Görsel tasarım ve sanat yönü (rainpunk/dieselpunk, turkuaz vurgular, sıcak iç mekan ışıkları) ayrı bir "art bible" dokümanına aittir; UI kodu başlamadan önce yazılmalıdır.
- Ses, hikâye metinleri, diyalog: kapsam dışı.
- Hesap sistemi, eşleştirme, lobi: M6 sonrası.
- Anti-cheat: kapsam dışı; co-op ve güvenilir ortam varsayımı.
