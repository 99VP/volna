# Volna

A personal music player for Android. Volna searches for songs on YouTube and
SoundCloud, builds a playlist around your taste, plays music in the background,
and can also play audio files stored on the phone.

Volna is not affiliated with YouTube, Google, SoundCloud, Shazam, Apple, or
Deezer. Downloading and streaming third-party content may violate their terms
and copyright law. Use it for personal purposes at your own risk.

## What it does

- **Search** for songs on YouTube and SoundCloud. The search is limited to
  music results, so podcasts, playlists, interviews, and ordinary videos are
  filtered out.
- **My Wave** builds a selection from your favorites, listening history, likes,
  skips, language, mood, and sound character. It tries to keep one style while
  avoiding the same song and the same artist too often.
- **Trends** shows Russian and international charts collected from several
  public sources.
- **Phone** plays local audio files from the device.
- **Player** shows the current track, a queue, favorite/like controls, repeat,
  shuffle, downloads, and a share button. Playback continues in the background
  with a notification and lock-screen controls.
- **Watch** is a separate video player. It pauses the main music player, plays a
  YouTube link or a direct video URL, and can share a “watch together” link.
  The link opens the app when it is installed; otherwise the page sends the
  person to GitHub Releases.
- **Themes** change with a wave that starts from the tapped theme. If you tap
  another theme while the first wave is still moving, both waves continue and
  the interface changes smoothly.
- Long song titles scroll like a news ticker: pause, move right-to-left, pause,
  repeat.
- **YouTube without a VPN** uses a local DPI-bypass proxy (ByeDPI). It runs only
  on the phone, does not send traffic to a third-party server, and does not need
  root. If another VPN is active, the local bypass pauses to avoid a conflict.
- The app checks GitHub Releases for updates. A dismissible banner appears when
  a newer version exists; the same update action stays available in Settings.
  The current version is shown in the bottom-right corner of Settings.

## Install

Download the latest APK from
[GitHub Releases](https://github.com/99VP/volna/releases/latest) and open it on
an Android 10 or newer device. Allow installation from unknown sources when
Android asks.

The app is Android-only. There is no iOS build.

Android may show a Play Protect message such as “app not scanned” for software
installed outside Google Play. This is an OS-level warning for sideloaded apps,
not a virus report. The release APK is signed with the project’s release key; no
signing passwords or API keys are stored in the repository. A sideloaded app
always requires a confirmation tap, and this warning cannot be removed from
inside the app. Removing it completely requires distribution through Google Play
or Android developer verification.

## Privacy

- No accounts, analytics, ads, or tracking.
- Network access is used only for search, playback, downloads, and update
  checks.
- Favorites, history, settings, playlists, and game saves stay on the device.
- The media service is not exported to other apps.
- Permissions: internet, network state, foreground media playback,
  notifications, reading audio, and installing app updates. No contacts,
  location, or similar access.

## Build

Requirements: JDK 17, Android SDK with compileSdk 35 and build-tools 35.0.0,
CMake 3.22.1, and Android NDK 27.2.12479018 (needed for the local DPI bypass).

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/`.

A release build uses `keystore.properties` from the project root when it exists.
The file is ignored by git; without it, the release is signed with the debug
key. Signing passwords are never stored in the repository.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

Volna uses [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
and [ByeDPI](https://github.com/hufrea/byedpi). Their licenses and attribution
are included with the project.

---

<details>
<summary><b>Русский</b></summary>

## Что делает приложение

«Волна» — личный музыкальный плеер для Android. Он ищет песни на YouTube и
SoundCloud, собирает подборку под вкус, играет музыку в фоне и умеет
воспроизводить файлы с телефона.

- **Поиск** по YouTube и SoundCloud. Выдача ограничена музыкой: подкасты,
  плейлисты, интервью и обычные видео отсеиваются.
- **Моя волна** собирается из избранного, истории, лайков, скипов, языка,
  настроения и характера звука. Старается держать один стиль и не повторять
  одну песню и одного артиста слишком часто.
- **Тренды** — русские и зарубежные чарты из нескольких открытых источников.
- **Телефон** — музыка, которая лежит на устройстве.
- **Плеер** — текущий трек, очередь, избранное, повтор, перемешивание,
  скачивание и «поделиться». Музыка играет в фоне, есть уведомление и экран
  блокировки.
- **Просмотр** — отдельный видеоплеер. Он ставит музыку на паузу, играет ссылку
  с YouTube или прямой видеофайл и умеет делиться ссылкой «смотреть вместе».
  Если приложение установлено, ссылка открывает его; если нет — страницу
  загрузки на GitHub.
- **Темы** меняются волной из точки нажатия. Если нажать другую тему, пока
  первая волна ещё идёт, обе продолжаются, а интерфейс меняется плавно.
- Длинные названия песен двигаются как бегущая строка: пауза, движение справа
  налево, снова пауза.
- **YouTube без VPN** — локальный обход DPI (ByeDPI). Он работает только на
  телефоне, не отправляет трафик на чужой сервер и не требует root. Если
  включён другой VPN, обход приостанавливается, чтобы не конфликтовать.
- Приложение проверяет обновления на GitHub. Если есть новая версия, появляется
  закрываемая плашка; обновление также остаётся в настройках. Номер версии
  показан справа снизу в настройках.

### Установка

Скачай APK из [GitHub Releases](https://github.com/99VP/volna/releases/latest)
и открой на Android 10 или новее. Разреши установку из неизвестных источников,
когда система попросит.

Приложение только для Android. Версии для iOS нет.

Android может показать предупреждение Play Protect «приложение не проверено»
для программ, установленных вне Google Play. Это системное предупреждение для
любого APK со стороны, а не отчёт о вирусе. Release-APK подписан ключом
проекта; паролей подписи и API-ключей в репозитории нет. Полностью убрать это
предупреждение изнутри приложения нельзя: для этого нужна публикация в Google
Play или проверка разработчика Android.

### Приватность

Нет аккаунтов, аналитики, рекламы и слежки. Сеть нужна только для поиска,
воспроизведения, скачивания и проверки обновлений. Избранное, история,
настройки, плейлисты и сохранения игр остаются на устройстве.

### Сборка

Нужны JDK 17, Android SDK (compileSdk 35, build-tools 35.0.0), CMake 3.22.1 и
Android NDK 27.2.12479018.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK появится в `app/build/outputs/apk/`.

### Лицензия

GNU GPL v3.0. Подробности в [LICENSE](LICENSE).

</details>
