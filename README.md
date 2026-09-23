# ChatTranslator — перевод чата для сервера Hytale

Плагин переводит чат между русским и английским через DeepL: каждый игрок видит
сообщения на своём языке. Язык игрока определяется по языку игры при первом входе,
игрок может сменить его командой `/lang ru`, `/lang en`, `/lang auto` или `/lang off`.
Фразы, которые уже переводились, берутся из памяти переводов, а не из DeepL.

- Скачать готовый jar: <https://github.com/ayaft1445-eng/hytaleplug/releases/tag/chattranslator-latest>
- Установка, настройка ключа DeepL и все команды: [INSTALL-RU.md](INSTALL-RU.md)

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
