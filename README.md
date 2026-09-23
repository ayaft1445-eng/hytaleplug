# ChatTranslator — перевод чата для сервера Hytale

Плагин переводит чат между русским и английским: каждый игрок видит сообщения на своём
языке. Язык игрока определяется по языку игры при первом входе, игрок может сменить его
в любой момент командой `/tr ru`, `/tr en`, `/tr auto` или `/tr off`. Переводят DeepL
(если вписан ключ и сервис доступен из страны сервера) и бесплатный MyMemory — по очереди.
Фразы, которые уже переводились, берутся из памяти переводов.

- Скачать готовый jar: <https://github.com/ayaft1445-eng/hytaleplug/releases/tag/chattranslator-latest>
- Установка, обновление с 1.0.0, настройки и все команды: [INSTALL-RU.md](INSTALL-RU.md)

## Сборка

Нужен JDK 25. Проект собран на шаблоне
[Hytale Plugin Template](https://github.com/HytaleModding/plugin-template) с Gradle-плагином
[Hytale Gradle Plugin](https://github.com/AzureDoom/Hytale-Gradle-Plugin).

```bash
./gradlew build        # jar появится в build/libs/, заодно прогоняются тесты
```

На Windows — `gradlew.bat build`. При каждом push в GitHub Actions собирает jar и
выкладывает его в Releases под тегом `chattranslator-latest`.

Ключ DeepL в репозиторий не кладётся: он хранится только в `config.json` плагина на сервере.
