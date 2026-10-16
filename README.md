# uxmdiscord-multiserver

**Türkçe** · [**English**](README.en.md)

![Java 21+](https://img.shields.io/badge/Java-21%2B-F4BD60?style=flat-square)
![Paper API 1.21+](https://img.shields.io/badge/Paper_API-1.21%2B-55C7F4?style=flat-square)
[![GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-4ADB9B?style=flat-square)](LICENSE)
[![Son sürüm](https://img.shields.io/github/v/release/SerhatDew/uxmdiscord-multiserver?display_name=tag&style=flat-square&color=F4BD60&label=Son%20s%C3%BCr%C3%BCm)](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest)

uxmDiscordSync için çoklu sunucu (multi-server / BungeeCord / Velocity) senkronizasyon hook eklentisi. Birden fazla Paper alt sunucusunda tek bir Discord botunun ve paylaşımlı veritabanının çakışmasız, koordineli çalışmasını sağlar.

[**En yeni JAR'ı indir**](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest/download/uxmdiscordsync-multiserver.jar) · [Sürüm notları](https://github.com/SerhatDew/uxmdiscord-multiserver/releases/latest)

[Özellikler](#özellikler) · [Gereksinimler](#gereksinimler) · [Mimari](#mimari) · [Kurulum](#kurulum) · [Yapılandırma](#yapılandırma) · [Derleme](#derleme) · [Lisans](#lisans)

---

## Özellikler

- **Koordinatör Mimarisi:** Yalnızca tek bir koordinatör sunucu Discord Gateway'e (JDA) bağlanır; slash komutları ve buton etkileşimlerini yönetir. Diğer alt sunucular bot oturumunu mükerrer açmaz, Discord rate-limit engeline takılmaz.
- **Paylaşımlı Eşleme (Shared Linking):** Oyuncu hesabını herhangi bir alt sunucuda bir kez eşlediğinde, durum tüm ağ genelinde senkronize edilir.
- **Esnek Ödül Modları:** `per-server` (her sunucuda ayrı ödül hakkı) veya `global` (tüm ağda tek seferlik ödül) desteği.
- **Dağıtık Sunucu Kira (Lease) Mekanizması:** Kalp atışı (heartbeat) tabanlı veritabanı kira yönetimiyle aynı sunucu adının birden fazla açık kalmasını engeller, çevrimdışı sunucuları tespit eder.
- **Çapraz Sunucu Komut Kuyruğu:** Discord üzerinden tetiklenen ödül ve rolleri hedef sunucuya ve oyuncuya SQL tabanlı komut kuyruğuyla iletir; teslim edilmemiş kayıtlar yeniden denenebilir.
- **PlaceholderAPI ve LuckPerms Desteği:** İsteğe bağlı olarak yetki ve yer tutucu entegrasyonu sağlar; bu API'ler JAR içine kopyalanmaz, sunucudaki eklentilerden alınır.

## Gereksinimler

- Java 21 veya üzeri.
- Paper (veya çatalları) 1.21 / 1.21.1 / 1.21.4.
- Ana eklenti: `uxmDiscordSync` v2.1.0 (ortak MySQL/MariaDB bağlantısıyla yapılandırılmış olmalıdır).
- İsteğe bağlı: `LuckPerms`, `PlaceholderAPI`.

## Mimari

```text
┌────────────────────────────────────────────────────────┐
│                   DISCORD GATEWAY                      │
└───────────────────────────┬────────────────────────────┘
                            │ (Tek JDA Bağlantısı)
               ┌────────────▼────────────┐
               │   KOORDİNATÖR SUNUCU    │
               │      (Örn: lobi)        │
               └────────────┬────────────┘
                            │ SQL Veritabanı
          ┌─────────────────┴─────────────────┐
          ▼                                   ▼
┌───────────────────┐               ┌───────────────────┐
│   ALT SUNUCU 1    │               │   ALT SUNUCU 2    │
│  (Örn: survival)  │               │   (Örn: skyblock) │
└───────────────────┘               └───────────────────┘
```

1. **Koordinatör Sunucu:** Botu aktif çalıştırır, Discord üzerindeki etkileşimleri ve eşleme butonlarını dinler.
2. **Alt Sunucular:** Ana eklentinin başlatabildiği Gateway bağlantısı hook tarafından durdurulur; alt sunucular yalnızca paylaşılan veritabanından eşleme durumu ve komutları okur.

Komut ve ödül kayıtları işlenmeden önce kısa süreli SQL sahiplik kirası alır. Başarılı Bukkit işlemi sonrasında kayıt onaylanır; işlem yarıda kesilirse kira süresi dolduğunda başka bir sunucu yeniden deneyebilir. Bukkit komutları dış sistem olduğundan, işlem ile onay arasındaki çökme penceresinde yinelenen uygulama mümkündür; kuyruk tam olarak bir kez (exactly-once) semantiği vaat etmez.

## Kurulum

1. Ağdaki tüm Paper sunucularına `uxmDiscordSync` v2.1.0 eklentisini kurun ve hepsini **aynı MySQL/MariaDB veritabanına** bağlayın.
2. `uxmdiscordsync-multiserver.jar` dosyasını her alt sunucunun `plugins/` klasörüne ekleyin.
3. Sunucuları bir kez başlatıp durdurun ve `plugins/uxmDiscordSync-MultiServer/config.yml` dosyasını düzenleyin.
4. Yalnızca bir sunucuyu koordinatör (`discord-coordinator-server`) olarak belirleyin (genellikle lobi sunucusu).

## Yapılandırma

`plugins/uxmDiscordSync-MultiServer/config.yml`:

```yaml
multi-server:
  # Çoklu sunucu modunu açar/kapatır
  enabled: true

  # Bu sunucunun adı ("auto" verilirse uxmDiscordSync'in server-name değeri kullanılır)
  server-name: "auto"

  # Discord botunu aktif yönetecek koordinatör sunucu adı
  discord-coordinator-server: "main"

  # Hesap eşleme modu:
  # - shared: Bir kez eşleyen oyuncu tüm sunucularda eşlenmiş kabul edilir
  # - separate: Her sunucuda ayrı eşleme gerekir
  link-mode: "shared"

  # Eşleme ödülü modu:
  # - per-server: Oyuncu her alt sunucuda ayrı ödül alabilir
  # - global: Tüm ağda yalnızca bir kez ödül talep edilebilir
  reward-mode: "per-server"
```

## Derleme

Projeyi kaynaktan derlemek için Maven 3 ve Java 21 gereklidir:

```sh
mvn clean package
```

Çıktı JAR dosyası `target/uxmdiscordsync-multiserver.jar` konumunda oluşur.

## Lisans

Bu proje **GNU General Public License v3.0** ile lisanslanmıştır. Ayrıntılar için [LICENSE](LICENSE) dosyasına bakın.
