# LocalVPN для iPhone

Тот же интерфейс, что и в Android-версии: страница `app/src/main/assets/index.html` открывается
во встроенном браузере, а работу с VPN делает сетевое расширение `PacketTunnel`.

## Что работает
- туннели AmneziaWG/WireGuard и большая кнопка с WARP (маскировка Jc/Jmin/Jmax, S1–S4, H1–H4, I1);
- прокси-туннели SOCKS5 на весь iPhone (движок hev-socks5-tunnel через Tun2SocksKit);
- резервная копия в папку в «Файлах» (например, iCloud Drive).

## Чего нет на iPhone
- «VPN для одного приложения» — iOS не даёт обычным приложениям выбирать программы;
- LocalProxy для Telegram (127.0.0.1:1080) — свёрнутое приложение iOS усыпляет;
- кнопки «Проверить обновления» — обновления приходят через App Store / TestFlight.

## Сборка
GitHub Actions → «Сборка iOS» (ветка `ios` или кнопка «Run workflow»). Сейчас собирается без
подписи — это проверка, что код компилируется. Установить такую сборку на iPhone нельзя.

Чтобы ставить на телефон через TestFlight, нужен платный аккаунт Apple Developer Program:
в нём создаются идентификаторы `app.localvpn` и `app.localvpn.tunnel` с возможностями
Network Extensions (Packet Tunnel) и App Groups (`group.app.localvpn`), а в секреты GitHub
добавляются сертификат, профили и ключ App Store Connect API.

Движок AmneziaWG собирается из `amneziawg-apple` (MIT) скриптом `scripts/build-wg-go.sh`;
Swift-часть WireGuardKit лежит в `PacketTunnel/WireGuardKit`.
