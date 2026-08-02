# MeshTalk — messenger with Bluetooth/WiFi/Internet mesh delivery

Telegram-style UI, but message delivery works over three transports with automatic
fallback, plus phone-to-phone relay when no direct connection exists — same idea as
bitchat, extended with an internet path for when you have connectivity.

## Что уже сделано (рабочий каркас проекта)

| Модуль | Файл | Статус |
|---|---|---|
| Проект/сборка | `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts` | ✅ готово |
| Права доступа | `AndroidManifest.xml` | ✅ готово |
| Модели данных | `data/model/Models.kt` | ✅ готово |
| Шифрование (Double Ratchet, forward secrecy) | `crypto/DoubleRatchet.kt`, `RatchetSessionManager.kt` | ✅ готово |
| Транспорт: Bluetooth+WiFi (Nearby Connections) | `transport/NearbyTransport.kt` | ✅ готово — handshake + адресная отправка по peerId |
| Транспорт: Интернет (relay-сервер) | `transport/InternetTransport.kt` | ✅ готово — реальный WebSocket-клиент (OkHttp) + сервер в `relay-server/` |
| Mesh-роутер (flood + dedup + TTL) | `mesh/MeshRouter.kt` | ✅ готово |
| Foreground-сервис (держит mesh активным в фоне) | `mesh/MeshRelayService.kt` | ✅ готово |
| UI: список чатов | `ui/screens/ChatListScreen.kt` | ✅ готово |
| UI: экран переписки (текст + геометки) | `ui/screens/ChatScreen.kt` | ✅ готово |
| UI: добавление контакта через QR | `ui/screens/AddContactScreen.kt` | ✅ готово |
| Геолокация | `location/LocationProvider.kt` | ✅ готово |
| MainActivity + запрос разрешений + навигация | `MainActivity.kt` | ✅ подключён к реальному репозиторию |
| Room: сущности, DAO, база | `data/repository/Entities.kt`, `Daos.kt`, `MeshTalkDatabase.kt` | ✅ готово |
| ChatRepository (крипто + БД + mesh) | `data/repository/ChatRepository.kt` | ✅ готово |
| Relay-сервер (Node.js) | `relay-server/server.js` | ✅ готово |
| Голосовые сообщения | `attachments/VoiceRecorder.kt` | ✅ готово |
| Вложения (файлы) | `attachments/AttachmentStorage.kt` | ✅ готово |
| Потоковая передача крупных вложений | `NearbyTransport.kt` (FILE payload), `InternetTransport.kt` (чанки) | ✅ готово |

Проект в текущем виде **открывается в Android Studio и запускается**. Список чатов,
переписка, отправка текста/геометки, ретрансляция и handshake ключей — на реальных
данных из Room, без демо-заглушек. Чтобы увидеть обмен сообщениями, нужно установить
на два физических устройства (см. раздел "Как собрать").

## Реализованные шаги (весь исходный план)

1. ✅ **Room-репозиторий** — постоянное хранилище чатов/сообщений/пиров
   (`data/repository/ChatRepository.kt`, `Entities.kt`, `Daos.kt`, `MeshTalkDatabase.kt`).
2. ✅ **Связка endpointId ↔ peerId** в `NearbyTransport` — адресная отправка конкретному
   устройству, а не всегда флуд всем.
3. ✅ **Handshake обмена публичными ключами** при Nearby-соединении (`HELLO{peerId, publicKey}`).
4. ✅ **Internet relay-сервер** — `relay-server/server.js` (Node.js + `ws`) + реальный
   WebSocket-клиент в `transport/InternetTransport.kt` (OkHttp, переподключение с
   экспоненциальной задержкой).
5. ✅ **Экран создания закрытого чата через QR** — `ui/screens/AddContactScreen.kt`,
   `ui/components/QrCodeGenerator.kt` (генерация), `ui/components/QrScannerView.kt`
   (сканирование, CameraX + ML Kit), формат приглашения в `data/model/InviteCode.kt`.
6. ✅ **Геолокация** — `location/LocationProvider.kt` (`FusedLocationProviderClient`),
   геометка в чате кликабельна и открывается во внешней карте.
7. ✅ **Double Ratchet** — `crypto/DoubleRatchet.kt` + `RatchetSessionManager.kt` +
   `RatchetSessionEntity.kt`. У каждого сообщения свой ключ (forward secrecy);
   компрометация одного ключа не раскрывает ни прошлые, ни будущие сообщения.
8. ✅ **Голосовые сообщения и файлы** — `attachments/VoiceRecorder.kt` (запись через
   `MediaRecorder` в AAC/M4A), `attachments/AttachmentStorage.kt` (расшифрованные
   вложения хранятся в приватном хранилище приложения, не в самой Room-базе — только
   путь к файлу лежит в `MessageEntity`). Вложения идут через тот же зашифрованный
   Double Ratchet конверт, что и текст — `MessagePayload.Audio`/`MessagePayload.File`
   в `data/model/Models.kt`. В `ChatScreen.kt` — кнопка микрофона (тап — начать/закончить
   запись, показывается пузырь с воспроизведением через `MediaPlayer`) и кнопка
   «скрепка» (системный выбор файла, пузырь с именем/размером, тап открывает файл во
   внешнем приложении через `FileProvider`).

9. ✅ **Потоковая передача крупных вложений** — большие голосовые/файлы больше не
   упираются в лимиты одного пакета:
   - `transport/NearbyTransport.kt`: ciphertext крупнее 32 КБ уходит не как BYTES-payload,
     а как настоящий Nearby `Payload.fromFile()` — чанкинг, контроль потока и повторные
     попытки на уровне Bluetooth/WiFi берёт на себя библиотека Google, а не наш код.
     Небольшой BYTES-заголовок (`LargeEnvelopeMeta`) с остальными полями конверта
     отправляется отдельно и сопоставляется с файлом по `payloadId`, когда передача завершена.
   - `transport/InternetTransport.kt`: у WebSocket нет встроенного чанкинга, поэтому для
     ciphertext крупнее 48 КБ реализовано ручное разбиение на кадры `ENVELOPE_CHUNK`,
     пересобираемые на другой стороне (с таймаутом на брошенные передачи).
   - `relay-server/server.js` теперь маршрутизирует по `targetPeerId` **любой** кадр,
     не разбирая его `type` — так что серверу не нужно ничего знать про чанки,
     будущие изменения протокола не потребуют правок сервера.
   - Раз транспортный потолок снят, `ChatRepository.MAX_ATTACHMENT_BYTES` поднят до 25 МБ
     — теперь это не лимит транспорта, а разумная граница по памяти телефона (шифрование
     всё ещё происходит одним куском, не по частям — см. комментарий в коде).

## Известные упрощения (не блокируют работу, но стоит учитывать)

- **Первое сообщение в новом чате** защищено так же, как в старой статичной схеме
  (ratchet стартует, используя identity-ключ вместо подписанного prekey — см. комментарий
  в начале `DoubleRatchet.kt`). Начиная со второго сообщения — полный forward secrecy.
  Закрывается добавлением настоящих X3DH prekeys, для чего нужен небольшой сервер
  публикации ключей.
- **Групповые чаты** шифруются попарно (свой ratchet и ciphertext на каждого участника),
  а не общим групповым ключом — просто и безопасно для небольшой компании друзей, но
  не масштабируется на большие группы (Signal использует sender keys для этого).
- **Очередь `pending_envelopes`** хранит plaintext-JSON во внутреннем поле `ciphertext`
  до появления ключа пира (см. комментарий в `queuePending()`) — работает, но название
  поля вводит в заблуждение, стоит переименовать при рефакторинге.
- **`onNewChatClick`** ведёт на экран QR — если оба пира уже когда-то виделись через
  Nearby handshake, но ни разу не сканировали QR друг друга, отдельного способа начать
  с ними чат из UI пока нет (можно добавить экран со списком уже известных `peers`).
- **Повторное сканирование** уже известного друга создаёт новый чат вместо переиспользования
  старого — комментарий с деталями в `addScannedContactAndOpenChat()`.
- **Очередь на relay-сервере** для оффлайн-получателей — только в памяти, теряется при
  перезапуске сервера (см. `relay-server/README.md`).
- **Чанкированная передача не возобновляется** — если приложение/соединение обрывается
  на середине передачи крупного вложения через интернет-relay, накопленные чанки
  выбрасываются по таймауту (`InternetTransport.CHUNK_BUFFER_TIMEOUT_MS`, 2 минуты), и
  отправителю нужно будет отправить вложение заново целиком, а не продолжить с места
  обрыва. Через Nearby Connections восстановление после разрыва частично берёт на себя
  сама библиотека, но тоже не гарантировано.
- **Room DB не зашифрована на диске** — ratchet-ключи и история сообщений хранятся в
  обычной SQLite-базе. Для дополнительной защиты при физическом доступе к телефону
  стоит перейти на SQLCipher.

## Relay-сервер

Код и инструкции по развёртыванию — в `relay-server/`. Минимальный WebSocket-роутер
на Node.js, пересылает зашифрованные конверты по `peerId`, содержимого сообщений не
видит.

## Как собрать

Открыть папку `meshtalk/` в Android Studio (Koala+), дождаться Gradle sync,
запустить на двух физических устройствах (эмулятор не увидит реальный BT/WiFi соседей).
