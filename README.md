# Volna

A personal music player for Android: music search, personal recommendations,
charts, local files, video viewing, and themes in one app.

## Features

- Music search and a personal “My Wave” selection.
- Charts, local library, downloads, and playlists.
- Background playback, a queue, and a full-screen video player.
- Themes, a system-adaptive icon, and in-app update checks.

## Install

Download the latest APK from
[GitHub Releases](https://github.com/99VP/volna/releases/latest) and open it on
Android 10 or newer. Allow installation from unknown sources when Android asks.

## Privacy

No accounts, analytics, or ads. Favorites, history, settings, playlists, and
game saves stay on the device.

## Build

JDK 17, Android SDK 35, CMake 3.22.1, and NDK 27.2.12479018 are required.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/`.

## License

GNU General Public License v3.0. See [LICENSE](LICENSE).

---

<details>
<summary><b>Русский</b></summary>

«Волна» — личный музыкальный плеер для Android: поиск музыки, персональная
подборка, чарты, файлы с телефона, видео и темы в одном приложении.

### Установка

Скачай APK из [GitHub Releases](https://github.com/99VP/volna/releases/latest) и
открой на Android 10 или новее. Разреши установку из неизвестных источников.

### Приватность

Нет аккаунтов, аналитики и рекламы. Избранное, история, настройки, плейлисты и
сохранения игр остаются на устройстве.

### Сборка

Нужны JDK 17, Android SDK 35, CMake 3.22.1 и NDK 27.2.12479018.

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

APK появится в `app/build/outputs/apk/`.

### Лицензия

GNU GPL v3.0. Подробности в [LICENSE](LICENSE).

</details>
