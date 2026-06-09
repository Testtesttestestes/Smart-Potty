# Smart Potty / Baby Tracker

Нативное Android-приложение на Kotlin + Jetpack Compose для ведения профилей детей, событий трекера и BLE-настроек умного горшка.

## Как открыть в Android Studio

1. Откройте Android Studio.
2. Выберите **Open** и укажите корень этого репозитория.
3. Дождитесь Gradle Sync. Проект использует Gradle Wrapper, поэтому Android Studio сама скачает подходящую версию Gradle.
4. Если Android Studio предложит установить Android SDK / Build Tools, согласитесь.
5. Запускайте конфигурацию `app` на эмуляторе или физическом устройстве.

## Требования

- Android Studio с JDK 17.
- Android SDK 34 или новее.

## Сборка из терминала

```bash
./gradlew :app:assembleDebug
```
