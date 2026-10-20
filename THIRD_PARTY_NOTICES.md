# Third-party notices

Volna is distributed under the GNU General Public License v3.0. It uses the
following open-source components:

## NewPipeExtractor

- Project: https://github.com/TeamNewPipe/NewPipeExtractor
- License: GNU General Public License v3.0 or later
- Used for searching and extracting media stream URLs from YouTube and
  SoundCloud.

## ByeDPI

- Project: https://github.com/hufrea/byedpi
- License: MIT
- Source: `app/src/main/cpp/byedpi/`
- Used as a local SOCKS5 proxy that applies TCP-level DPI evasion. No traffic is
  sent to a remote server.

## ByeDPIAndroid

- Project: https://github.com/dovecoteescapee/ByeDPIAndroid
- License: GNU General Public License v3.0
- The JNI wrapper and part of the Android integration are based on this project.
- Used only for the local proxy mode; the VPN mode and its native tunnel are not
  included.

## Other libraries

Jetpack Compose, Media3/ExoPlayer, OkHttp, Coil, Kotlin coroutines and AndroidX
are used under their respective Apache 2.0 licenses.
