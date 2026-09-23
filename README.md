# ChatTranslator — перевод чата для сервера Hytale

Плагин переводит чат между русским и английским: каждый игрок видит сообщения на своём
языке. Язык игрока определяется по языку игры при первом входе, игрок может сменить его
в любой момент командой `/tr ru`, `/tr en`, `/tr auto` или `/tr off`.

Сообщение переводится от простого к сложному: частые фразы чата — встроенным
разговорником и файлом `phrases.txt` сервера, отдельные слова — словарём на ~120 тысяч
словоформ, уже переводившиеся фразы — из памяти переводов, и только остальное —
сервисом (DeepL, если вписан ключ и сервис доступен из страны сервера, затем бесплатный
MyMemory). Сообщения плагина выделены цветом.

- Скачать готовый jar: <https://github.com/ayaft1445-eng/hytaleplug/releases/tag/chattranslator-latest>
- Установка, обновление, настройки, свои фразы и все команды: [INSTALL-RU.md](INSTALL-RU.md)

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

## Словарь

`src/main/resources/dictionary/ru-en.tsv` и `en-ru.tsv` собраны скриптом
`tools/build_dictionary.py` из данных [OpenRussian.org](https://en.openrussian.org)
(<https://github.com/Badestrand/russian-dictionary>) и распространяются по лицензии
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/), как и исходные данные.
Для отбора слов использованы частоты [wordfreq](https://github.com/rspeer/wordfreq),
для английских форм — [lemminflect](https://github.com/bjascob/LemmInflect).
