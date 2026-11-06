# LocalVPN

Android-приложение для VPN-туннелей на протоколе WireGuard: список туннелей, создание через «+», настройки интерфейса и пиров, FAQ, режим «VPN только для одного приложения» с избранным.

Подключение работает через открытую библиотеку [amneziawg-android](https://central.sonatype.com/artifact/com.zaneschepke/amneziawg-android) (Apache 2.0): она понимает обычные WireGuard-конфиги и конфиги AmneziaWG с маскировкой трафика. Бесплатный конфиг Cloudflare WARP приложение получает само, по образцу открытого проекта [wgcf](https://github.com/ViRb3/wgcf). LocalVPN не связан с проектами WireGuard, AmneziaWG и Cloudflare; WireGuard — товарный знак Jason A. Donenfeld.

## Как получить APK

Сборку делает GitHub — на компьютер ничего ставить не нужно.

1. После каждого изменения в ветке `main` запускается сборка во вкладке **Actions**. Её можно запустить и вручную: Actions → «Сборка APK» → **Run workflow**.
2. Через 5–10 минут готовый файл появится в разделе **Releases** справа на главной странице репозитория: откройте последнюю версию и скачайте `LocalVPN-1.0.N.apk`. Удобно делать это прямо с телефона.
3. Откройте файл на телефоне и разрешите установку из этого источника.
4. При первом включении туннеля Android спросит разрешение на VPN — нажмите «ОК».

## Ключ подписи (чтобы обновления ставились поверх)

Android обновляет приложение, только если новая версия подписана тем же ключом. Ключ хранится в секретах репозитория: **Settings → Secrets and variables → Actions → New repository secret**.

| Name | Что вставить |
|---|---|
| `KEYSTORE_BASE64` | ключ в виде одной длинной строки base64 |
| `KEYSTORE_PASSWORD` | пароль ключа |
| `KEY_ALIAS` | `localvpn` |

Без этих секретов сборка тоже работает, но каждый APK подписывается новым временным ключом: чтобы поставить следующую версию, старую придётся удалить (вместе с сохранёнными туннелями).

## Устройство проекта

- `web/tunnels.html` — интерфейс, тот же, что и в браузерной версии.
- `tools/build_web.py` — делает из него офлайн-страницу `app/src/main/assets/index.html` (локальные шрифты и библиотека ключей). После правок в `web/tunnels.html` запустите `python3 tools/build_web.py`.
- `app/src/main/java/app/localvpn/MainActivity.kt` — окно с интерфейсом и мост `window.Android` для страницы.
- `app/src/main/java/app/localvpn/VpnController.kt` — включение и выключение туннеля, статистика, понятные тексты ошибок.
- `app/src/main/java/app/localvpn/Warp.kt` — получение бесплатного конфига Cloudflare WARP.
- `.github/workflows/build.yml` — автоматическая сборка APK.

## Требования

Android 8.0 и новее.
