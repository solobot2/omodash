# Omodash: имя, пакет и иконка

Production application ID и namespace: `win.solobot.omodash`.
Debug: `win.solobot.omodash.debug`; сборка для проверки обновлений:
`win.solobot.omodash.updatetest`. Kotlin/Java-код и тесты находятся в
`app/src/{main,test,androidTest}/java/win/solobot/omodash/`.
Службы, действия broadcast, task-host protocol, USB-tools и проверки публикации
используют тот же namespace. Имя на экране и в настройках Android — **Omodash**.

При переименовании пакета требуется новая установка и повторный запуск USB
`setup`. Прежний набор USB-tools предназначался для прежнего приложения;
используйте набор, собранный вместе с новым APK. Новый пакет подписывается
имеющимся постоянным ключом: публичный сертификат уже выпущен на
`CN=Omodash, O=solobot2` и не привязан к идентификатору пакета.

До публикации первого релиза с новым application ID старый публичный update feed
будет отклоняться проверкой принадлежности обновления. Проверки пакета и подписи
при переименовании сохраняются.

## Иконка

Монограмма OD: у O скошены только левые углы, правые — прямые. Через общую
вертикаль O переходит в D с плавной внешней дугой. Буквы выполнены неоновыми
контурами с яркой сердцевиной и мягким свечением. В месте наложения свет
зелёной и оранжевой букв смешивается в режиме screen.
Фон чёрный; зелёный `#13DCA2` из ночного макета, оранжевый `#FF9D38`.
Цвета подтверждены пользователем 31.10.2026.

- `branding/omodash-icon.svg` — основной векторный исходник.
- `branding/omodash-icon-1024.png` — экспорт 1024×1024.
- `branding/omodash-icon-2048.png` — экспорт 2048×2048.
- `app/src/main/res/mipmap-anydpi-v26/` — adaptive icons на чёрном фоне.
- `app/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` — прозрачный adaptive foreground 108–432 px.
- `app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/` — PNG 48–192 px.

В adaptive foreground монограмма уменьшена, чтобы помещаться в безопасную область
круглой иконки вместе с ореолом. Все варианты строятся из одного SVG. Android-ресурсы
растеризуются со свечением: VectorDrawable не поддерживает SVG blur filters.
Для повторной генерации нужен `rsvg-convert` (librsvg):

```sh
python3 tools/generate_icons.py
```
