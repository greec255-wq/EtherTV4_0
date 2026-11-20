# EtherTV 1.3.0-dev — изменения интеграции CustomDiscs

- Источник видео теперь назначается вставкой удалённой YouTube-пластинки CustomDiscs в ET-1.
- Добавлен `CustomDiscTelevisionService` для событий вставки и извлечения CustomDiscs 2.7.0.
- Видеокадры по-прежнему идут через существующий Rinku → JPEG → Paper MapRenderer конвейер.
- Звук оставлен за CustomDiscs и его установленной интеграцией Simple Voice Chat/Radio.
- Добавлены команды верхнего уровня `/ether play` и `/ether pause`.
- Удалена ветка `/ether youtube ...`.
- Исправлена неоднозначность регистрации команды: helper переименован в `registerEtherCommand`.
- Сохранены камера EK-1, карты-экраны, рецепты, модели, текстуры и сеть телевизионных каналов.

Статус сборки и обязательные проверки описаны в `BUILD_STATUS.txt` и `docs/CUSTOMDISCS_TELEVISION_RU.md`.
