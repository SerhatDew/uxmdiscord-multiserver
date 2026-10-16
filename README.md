# uxmdiscord-multiserver

**Türkçe** · [**English**](README.en.md)

![Java 21+](https://img.shields.io/badge/Java-21%2B-F4BD60?style=flat-square) ![Paper API 1.21.1](https://img.shields.io/badge/Paper_API-1.21.1-55C7F4?style=flat-square)

`uxmDiscordSync` için Paper çoklu-sunucu hook'udur. Her Paper backend'inde çekirdek eklenti ve bu hook bulunur; aynı SQL veritabanı üzerinden tek Discord Gateway koordinatörü, eşleme ve komut kuyruğu paylaşılır. Hook'u Velocity/Bungee'ye koymayın ve aynı sunucuya iki kopya JAR yüklemeyin.

## Gereksinimler ve güvenlik

- Java 21+ ve çalışan, kendi lisans koşulları karşılanmış `uxmDiscordSync` 2.1.0. Derleme hedefi Paper API 1.21.1'dir; Leaf 26.2 / Java 25 üzerinde koordinatör/ikincil açılışı ve Discord bağlantısı gözlendi. Bu, tüm Paper sürümleri ve bütün özellikler için destek garantisi değildir.
- Tüm backend'lerde aynı **MySQL veya MariaDB** veritabanına erişim. Hook'un SQL'i SQLite/flat-file için tasarlanmamıştır; PostgreSQL uyumluluğu vaat edilmez.
- LuckPerms ve PlaceholderAPI entegrasyonlarıdır; API'ler JAR'a gömülmez. Ancak doğrulanan çekirdek 2.1.0 yansıma taraması başlangıçta LuckPerms API'si ister; kullanılan permission özelliğinden bağımsız olarak LuckPerms kurulmalıdır. PlaceholderAPI ilgili placeholder'lar kullanılıyorsa kurulmalıdır.
- Bot tokenı ve veritabanı parolası gizli tutulmalı, GitHub'a veya loglara yazılmamalıdır. Aşağıdaki örnekler yer tutucudur; kendi değerlerinizi değiştirin.

## Kurulum

1. Her backend'in `plugins/` dizinine aynı sürüm `uxmDiscordSync` ve `uxmdiscordsync-multiserver.jar` koyun. Koordinatör dahil her backend hook'u çalıştırır.
2. Önce çekirdek `plugins/uxmDiscordSync/config.yml` dosyasını, sonra hook'un `plugins/uxmDiscordSync-MultiServer/config.yml` dosyasını düzenleyin.
3. Aynı veritabanı, bot/guild ve Discord eşleme kanalı değerlerini tüm çekirdek kurulumlarında eşitleyin. Hook tabloları ilk başarılı açılışta oluşturur; mevcut sütun geçişleri de açılışta uygulanır.
4. Tam sunucu yeniden başlatın. Bu projede güvenilir bir reload komutu yoktur; yeniden yükleme sırasında lease/kuyruk güvenliği garanti edilmez.

## Hook ayarları (beş alan)

Dosya: `plugins/uxmDiscordSync-MultiServer/config.yml`.

```yaml
multi-server:
  enabled: true
  server-name: "leaf1"
  discord-coordinator-server: "leaf1"
  link-mode: "shared"
  reward-mode: "per-server"
```

| Alan | Tip / varsayılan | Etki |
|---|---|---|
| `multi-server.enabled` | boolean, `true` | Hook'u açar; `false` için hook pasif kalır. Ana Discord eklentisini veya onun Gateway oturumunu kapatan genel bir anahtar değildir. |
| `multi-server.server-name` | string, `"auto"` | Backend kimliğidir. `auto` (büyük/küçük harf duyarsız), boş veya eksik değer çekirdeğin `plugin.server-name` değerine düşer. Her çalışan backend için benzersiz, tutarlı küçük harfli ve en çok 32 karakterlik ad kullanın; aynı ad lease çakışmasına neden olur. |
| `multi-server.discord-coordinator-server` | string, `"main"` | Discord Gateway/JDA sahibi tek backend'in adı. Karşılaştırma büyük/küçük harf duyarsızdır; sunucu adlarını diğer SQL işlemleri için de aynı yazımla kullanın. |
| `multi-server.link-mode` | string, `shared` | `shared`: ağ genelindeki eşleme; `separate`: sunucu kimliğine özgü eşleme. Desteklenen iki yazımdan birini kullanın. |
| `multi-server.reward-mode` | string, `per-server` | `per-server`: sunucu başına ödül; `global`: ağ genelinde ödül. Teslimin çökme sınırları aşağıda açıklanmıştır; bu alan “exactly once” garantisi değildir. |

`server-name` benzersizliği otomatik olarak düzeltilmez: aynı adla ikinci süreç `uxm_server_instances` lease'ini alamaz. Koordinatör yoksa ikincil sunucular koordinatör rolünü devralmaz; yerel görevleri çalışabilse de Discord etkileşimlerini karşılayacak aktif koordinatör gerekir. Otomatik koordinatör seçimi/failover yoktur. Eski `multi-server.coordinator-server` alanı fallback olarak okunur; yeni configte yalnız `discord-coordinator-server` kullanın. Eksik enabled/link/reward/koordinatör ayarları çekirdek configinden de okunabilir; belirsizliği önlemek için beş alanı hook dosyasında açıkça yazın.

## Çekirdek uxmDiscordSync ayarları

Her backend'de çekirdeğin aşağıdaki alanlarını yapılandırın: `database.type`, `database.mysql` altındaki bağlantı alanları, `plugin.server-name`, `discord.token`, `discord.guild-id` ve `discord.linking.channel-id`. Veritabanı/bot/guild/kanal ortak, sunucu adı benzersiz olmalı. Hook'un `server-name` değeri ile çekirdeğin `plugin.server-name` değerini eşleştirin. Aşağıdaki dosya **birleştirilecek örnek parçadır**; üretilen configin diğer alanlarını silmeyin.

```yaml
# plugins/uxmDiscordSync/config.yml (minimal biçim; mevcut core sürümünüzün diğer anahtarlarını koruyun)
plugin:
  server-name: "leaf1"
database:
  type: mysql
  mysql:
    host: "db.example.invalid"
    port: 3306
    database: "uxmdiscordsync"
    username: "replace-me"
    password: "replace-me"
discord:
  token: "REPLACE_WITH_PRIVATE_TOKEN"
  guild-id: "REPLACE_WITH_GUILD_ID"
  linking:
    channel-id: "1559952275605299211"
```


`db.example.invalid` bağlantı kurulabilen bir adres değildir; kendi MySQL/MariaDB adresinizle, kullanıcı/parolayı da yetkili DB hesabıyla değiştirin. Hesabın hook tablolarında okuma/yazma/silme ve açılış şema geçişleri için `CREATE`/`ALTER` yetkisi gerekir; DB'yi internete kontrolsüz açmayın. Port tam sayı, Discord snowflake ID'leri tırnaklı metin olmalıdır.

### Discord uygulaması ve eşleme kanalı

1. Botu doğru Discord sunucusuna `bot` ve slash komutları için `applications.commands` kapsamlarıyla davet edin.
2. Çekirdek 2.1.0 kodu `GUILD_MEMBERS` ve `MESSAGE_CONTENT` intent'lerini ister; Developer Portal > Bot altında **Server Members Intent** ve **Message Content Intent** izinlerini etkinleştirin. Bunlar için confige uydurma `intents` anahtarı eklemeyin.
3. Eşleme için metin kanalı oluşturun; Geliştirici Modu ile kanal ID'sini alın. Bu ağ için seçilen örnek ID `1559952275605299211`'dir. Kendi sunucunuzda botun erişebildiği kanalın ID'sini yazın; ID tek başına erişim izni sağlamaz.
4. Botun kanalı görme, mesaj gönderme, embed bağlantıları ve mevcut paneli okuyacaksa mesaj geçmişi okuma izinlerini kontrol edin. Rol verme kullanıyorsanız ayrıca **Manage Roles** ve bot rolünün hedef rollerden yukarıda olması gerekir. Test için bota gereksiz Administrator yetkisi vermeyin.
5. Minecraft izinleri LuckPerms/çekirdek komut kurallarına, Discord rol/panel ayarları çekirdek configine aittir. Hook bunları otomatik oluşturmaz.

`proxy_online` gibi placeholder'lar çekirdek 2.1.0'da literal kalabilir; burada ağ çevrimiçi durumunun garantisi olarak kullanmayın. Daha yeni/base yapılandırma bunu ayrıca sağlayabilir. Rol eşlemesi bu hook tarafından tanımlanmaz; doğrulanmış çekirdek ayarınız yoksa uydurma rol anahtarı eklemeyin. Çekirdek 2.1.0'da LuckPerms API'si eksikse yansıma tabanlı başlangıç alanları başarısız olabilir; bu nedenle kullanılan özellikten bağımsız olarak doğrulanmış kurulumda LuckPerms kurulmalıdır.

### İki tam backend örneği

**leaf1 (koordinatör):** çekirdek `plugin.server-name: leaf1`, yukarıdaki token/guild/kanal ve ortak MySQL; hook:

```yaml
multi-server:
  enabled: true
  server-name: "leaf1"
  discord-coordinator-server: "leaf1"
  link-mode: "shared"
  reward-mode: "per-server"
```

**leaf2 (ikincil):** çekirdekte `plugin.server-name: leaf2` kullanın; DB, guild, token ve kanal leaf1 ile aynı kalsın. Hook'ta yalnız yerel `server-name` değişir, `discord-coordinator-server` yine `leaf1` olur:

```yaml
multi-server:
  enabled: true
  server-name: "leaf2"
  discord-coordinator-server: "leaf1"
  link-mode: "shared"
  reward-mode: "per-server"
```

İkincil çekirdek Gateway başlatırsa hook onu durdurur; bağlantı koordinatörde kalır. Bu, çekirdeğin oturumu hiç açmaya çalışmadığı anlamına gelmez. Eski 2.1.0 çekirdeği bu kasıtlı kapatmayı `Was shutdown trying to await status` / `Failed to start Discord bot` şeklinde loglayabilir. Koordinatör hazır mesajını ve ikincil hook'un rolünü esas alın; beklenmeyen koordinatör bağlantı hatasını bu açıklamayla gizlemeyin.

## Veritabanı, migration ve çökme davranışı

Hook gerçek DDL ile `uxm_server_instances(server_key VARCHAR(32) PRIMARY KEY, owner VARCHAR(36), expires_at BIGINT)`, `uxm_network_link_codes` (kod, UUID/kullanıcı, `server`, süre, Discord bilgisi, teslim ve claim sütunları) ve `uxm_network_commands` (hedef, komut, executed, zaman ve claim sütunları) tablolarını oluşturur. Inline `INDEX` sözdizimi reddedilirse indeksleri olmayan fallback DDL vardır; mevcut claim sütunları `ALTER TABLE` ile tamamlanır. Bunlar MySQL/MariaDB tarzı DDL'dir; PostgreSQL/SQLite destek sözü değildir.

Sunucu kirası ve komut/ödül claim süresi 30 saniyedir; kira her 10 saniyede yenilenir. Komut kuyruğu yaklaşık saniyede bir sorgulanır. Başarısız veya çevrimdışı oyuncuya teslim edilemeyen işin claim'i bırakılır; bekleyen iş yeniden alınabilir. Çöken sürecin işi süre dolunca aynı hedef kimlikle çalışan tüketici tarafından alınabilir; rastgele başka backend'e yönlendirilmez. Bukkit yan etkisi ile SQL onayı arasındaki çökme penceresinde tekrar uygulama mümkündür; “exactly once” garantisi yoktur. Ödül komutlarını mümkünse tekrar uygulanmaya dayanıklı tasarlayın. Eşleme kodu üretiminde en fazla 10 deneme vardır.

## Doğrulama ve sorun giderme

1. Her iki backend'i tam yeniden başlatın; yalnızca koordinatörün Gateway/listener hazır mesajını, diğerinin yerel queue/manager hazır mesajını doğrulayın.
2. SQL'de üç `uxm_*` tablosunun oluştuğunu ve `server_key` değerlerinin benzersiz olduğunu kontrol edin. İki backend aynı adı kullanıyorsa lease hatasını düzeltin.
3. Bir test hesabıyla leaf1'de eşleyin; shared modda leaf2'de aynı durumun göründüğünü ve ödülün seçilen moda uyduğunu kontrol edin.
4. Discord buton/komutunun hedef leaf'e ulaştığını, backend durdurulup lease süresi sonrası tekrar açıldığında kuyruğun yeniden işlediğini kontrol edin. Yanlış token/guild/kanal, ortak olmayan DB, eksik LuckPerms API veya geçersiz lisans loglarda başlangıç hatasına neden olur.

Alanların uygulaması: [MultiServerConfig.java](src/main/java/com/uxplima/discordsync/multiserver/config/MultiServerConfig.java), [paket varsayılanı](src/main/resources/config.yml), [ServerLease.java](src/main/java/com/uxplima/discordsync/multiserver/database/ServerLease.java), [NetworkLinkStore.java](src/main/java/com/uxplima/discordsync/multiserver/database/NetworkLinkStore.java), [CommandQueueStore.java](src/main/java/com/uxplima/discordsync/multiserver/database/CommandQueueStore.java).

## Açılış durumları

Diğer iki hook ile aynı altın/sarı robot bannerı gerçek plugin adı, sürümü ve platformu gösterir. `Yapılandırma yüklendi.` sonrasında sunucu/rol yazılır. Koordinatör `Bekleniyor` durumundan ancak JDA dinleyicisi bağlanınca süreli başarıya geçer; ikincil yerel kuyruk/yöneticiler hazırken başarı bildirir. `enabled: false` için `Pasif` yazılır. Bağımlılık, DB, kira veya listener hatası `Başlatılamadı; eklenti hazır değil.` ve asıl exception ile raporlanır. Yarım başlangıç ve temizlik hatasında başarılı kapanış mesajı yazılmaz.

## İndirme ve kaynaktan derleme

Hazır dosya: [son release](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest). Sunucuya yalnız `uxmdiscordsync-multiserver.jar` kurun; kaynak ZIP veya `original-` JAR'ı değil. `main` dalındaki rehber/kod en son commitleri anlatır; eski release'in değişiklikleri sürüm notlarına göre ayrıca kontrol edilmelidir.

Java 21+ ve Maven 3 ile, kendi lisans koşullarınıza uygun edindiğiniz uxmDiscordSync 2.1.0 JAR'ını yerel Maven deposuna yükleyin:

```sh
mvn install:install-file -Dfile=uxmDiscordSync-v2.1.0.jar -DgroupId=com.uxplima -DartifactId=uxmdiscordsync -Dversion=2.1.0 -Dpackaging=jar -DgeneratePom=true
mvn clean verify
```

Çıktı `target/uxmdiscordsync-multiserver.jar` olur. Çekirdek/üçüncü taraf JAR'lar bu repoda dağıtılmaz. Ayar değişikliklerinde config/JAR yedeği alın; token, parola, lisans ve oyuncu verilerini repoya commit etmeyin.

## Lisans

GNU GPL v3.0 — [LICENSE](LICENSE).
