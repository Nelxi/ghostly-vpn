<p align="center"><img src="desktopApp/icons/ghostly.png" width="120" alt="Ghostly"></p>

<h1 align="center">Ghostly VPN</h1>

<p align="center">
Открытый VPN-клиент на ядрах Xray и mihomo для Android, Windows, macOS и Linux.<br>
Подписки VLESS · VMess · Trojan · Shadowsocks · Hysteria2 · XHTTP · Reality · Clash.
</p>

<p align="center">
  <a href="https://github.com/Nelxi/ghostly-vpn/releases/latest"><img alt="Скачать" src="https://img.shields.io/github/v/release/Nelxi/ghostly-vpn?label=%D0%A1%D0%BA%D0%B0%D1%87%D0%B0%D1%82%D1%8C&color=a88dff&style=for-the-badge"></a>
  <img alt="Лицензия" src="https://img.shields.io/badge/license-GPL--3.0-7c5cf0?style=for-the-badge">
  <img alt="Ядра" src="https://img.shields.io/badge/core-Xray%20%C2%B7%20mihomo-8fe3c0?style=for-the-badge">
  <a href="https://ghostlynex.fun"><img alt="Сайт" src="https://img.shields.io/badge/ghostlynex.fun-0c0b13?style=for-the-badge&logo=ghost&logoColor=c9b8ff"></a>
</p>

<p align="center"><img src="docs/screenshots/desktop.png" width="860" alt="Ghostly на Windows"></p>

<p align="center">
  <img src="docs/screenshots/phone-off.png" width="200" alt="Не подключено: серая ночь, призрак спит">
  <img src="docs/screenshots/phone-on.png" width="200" alt="Под защитой: северное сияние">
  <img src="docs/screenshots/phone-account.png" width="200" alt="Аккаунт: баланс, подписка, устройства">
  <img src="docs/screenshots/phone-servers.png" width="200" alt="Серверы">
</p>

## «Ночь и свет»

Всё приложение — одна живая ночная сцена над морем, и цвет в ней означает защиту. Пока VPN выключен, мир серый:
луна пускает лучи, по воде бежит лунная дорожка, призрак спит над своим отражением. Стоит подключиться — цвет
растекается от призрака: загорается северное сияние, просыпаются светлячки, всё отражается в тёмной воде.
Каждая вкладка — свой ракурс того же мира: при переключении камера плавно отъезжает, призрак перелетает на своё
место, а звёзды, сияние и острова плывут с параллаксом. Сцена рисуется без размытия и тяжёлых эффектов,
поэтому одинаково плавная и на флагмане, и на простом телефоне.

## Скачать

| Платформа | Файл |
|---|---|
| Android 8+ | [Ghostly-Android.apk](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Android.apk) (arm64, подходит почти всем) · [универсальный](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Android-universal.apk) |
| Windows 10/11 | [Ghostly-Windows.exe](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Windows.exe) — обычный установщик, без прав администратора · [портативная](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Windows-Portable.zip) |
| macOS | [Apple Silicon](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-macOS-AppleSilicon.dmg) · [Intel](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-macOS-Intel.dmg) |
| Linux | [.deb](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Linux.deb) · [.rpm](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Linux.rpm) · [.tar.gz](https://github.com/Nelxi/ghostly-vpn/releases/latest/download/Ghostly-Linux.tar.gz) |
| iPhone | скоро |

Подписку можно купить на [ghostlynex.fun](https://ghostlynex.fun), но приложение работает с любой подпиской VLESS / VMess / Trojan / Shadowsocks / Hysteria2.

---

## Возможности

- **Подключение в одно касание** — коснись призрака: таймер сессии, скорость загрузки и отдачи в реальном времени.
- **Аккаунт Ghostly прямо в приложении** — подписка, баланс, устройства, продление и пополнение картой или по СБП без сайта, вход через Telegram.
- **Два ядра** — Xray (по умолчанию) и mihomo для подписок Clash с группами-селекторами.
- **16 языков** — следует языку устройства; персидский и арабский раскладываются справа налево.
- **Любые подписки** — ссылка, base64, JSON-конфиги Xray (как в Happ), диплинки `ghostly://`, `happ://add/`, QR-код, «Поделиться → Ghostly».
- **Быстрее обычных клиентов**
  - подписка скачивается наперегонки: напрямую, через зеркало и через уже поднятый туннель — побеждает первый ответ;
  - двухфазный пинг: мгновенный TCP-пинг всех серверов, затем реальная задержка через ядро (на ПК — один процесс Xray на все серверы);
  - на ПК подключение готово, как только локальный порт отвечает (~100 мс), без фиксированных задержек.
- **Умная маршрутизация** — российские сайты напрямую, остальное через VPN; свои правила «напрямую / через VPN / блок»; блокировка рекламы.
- **Сторож соединения** — каждые 20 секунд проверяет, что трафик реально идёт через туннель, и сам переходит на рабочий сервер.
- **Умные белые списки** — сам уходит на серверы белых списков при блокировках мобильного интернета и возвращается на обычные, как только ограничения снимут.
- **Автосмена сервера** при падении, автоподключение, запуск при включении устройства.
- **Раздельное туннелирование по приложениям** (Android), плитка в шторке, kill switch через системную «Постоянную VPN».
- **Локальный прокси** SOCKS5 + HTTP с логином и паролем (`ghostly_…`), настраиваемые порты, автоматический обход занятых портов.
- **ПК:** режим TUN (весь трафик) или системный прокси, трей, тёмный заголовок окна, отдельная десктопная раскладка.
- **Пулы трафика** Ghostly (белые списки / обычные серверы) с отдельными полосками и датой обновления.
- DNS через туннель (Cloudflare, Google, Quad9, AdGuard или свой), IPv6, MTU, мультиплекс, фрагментация TLS.

## Сборка

Нужны JDK 21 и Android SDK (для Android).

```bash
./gradlew :androidApp:assembleRelease       # APK по архитектурам + универсальный
./gradlew :desktopApp:run                   # запустить на ПК
./gradlew :desktopApp:packageDistributionForCurrentOS   # MSI/EXE, DMG или DEB/RPM
./gradlew :shared:jvmTest                   # тесты парсеров и сборщика конфигов
```

Ядро Xray скачивается при сборке и проверяется по sha256 (`gradle/libs.versions.toml`):
Android — [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite), ПК — [Xray-core](https://github.com/XTLS/Xray-core).
iOS-приложение собирается на macOS (см. `iosApp/`).

## Устройство

```
shared/        общий код (Kotlin Multiplatform + Compose Multiplatform)
  core/        модели, парсер ссылок и подписок, сборщик конфига Xray, контроллер
  ui/          весь интерфейс: тема, компоненты, экраны, десктопная оболочка
androidApp/    VpnService (TUN-дескриптор напрямую в ядро Xray), плитка, QR, автозапуск
desktopApp/    процесс Xray, системный прокси (Windows/macOS/Linux), трей, автозапуск
iosApp/        оболочка SwiftUI
```

## Лицензия

GPL-3.0. Ядро Xray — MPL-2.0, AndroidLibXrayLite — LGPL-3.0, шрифт Onest — SIL OFL 1.1 (`licenses/`).
