# DebianBox — настоящий Debian + рабочий стол на Android

Приложение скачивает rootfs Debian (arm64), запускает его через proot,
даёт терминал и графический рабочий стол XFCE (VNC + noVNC в WebView).

## Что нужно добавить вручную (бинарники proot)
Положите в `app/src/main/jniLibs/arm64-v8a/` три файла из пакетов Termux
(`proot`, `libtalloc`, `proot` loader — .deb из packages.termux.dev, распакуйте `ar x` + `tar xf`):

| Файл в jniLibs        | Откуда                                             |
|-----------------------|----------------------------------------------------|
| `libproot.so`         | `usr/bin/proot`                                    |
| `libproot-loader.so`  | `usr/libexec/proot/loader`                         |
| `libtalloc.so`        | `usr/lib/libtalloc.so.2` (переименовать)           |

## Сборка
Android Studio → Open → папка `debianbox` → Run (нужен телефон arm64, Android 8+).
Или: `gradle assembleDebug` (нужны Android SDK и Gradle).

## Запуск
1. «Установить» — скачает и распакует Debian.
2. «Терминал» — оболочка bash внутри Debian (root).
3. «Рабочий стол» — при первом запуске поставит XFCE, затем покажет его в приложении.

## Ограничения
- targetSdk = 28 (как у Termux), иначе Android 10+ запретит запуск бинарников из данных приложения. В Google Play так не опубликовать — только APK.
- Терминал упрощённый (без PTY): подходит для команд, не для vim/htop.
- Ссылка на rootfs в `Debian.kt` (ROOTFS_URL) — проверьте актуальную версию.
- Код не компилировался в этой среде (нет Android SDK) — возможны мелкие правки при первой сборке.
