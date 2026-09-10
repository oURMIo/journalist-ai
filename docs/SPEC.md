# Telegram News Bot — техническое задание для кодового агента

**Версия:** 1.0
**Стек:** Java 21, Spring Boot 3.x, PostgreSQL, Docker / Docker Compose
**Язык новостей:** английский
**Язык кода и коммитов:** английский. Язык общения с заказчиком: русский.

---

## 0. Как агенту работать с этим документом

1. Прочитай документ целиком **до** написания первой строки кода.
2. Раздел «§2 Ограничения Telegram Bot API» — самый важный. Там описано ограничение, которое ломает наивную реализацию. Не пропускай.
3. Реализуй по этапам из §12. После каждого этапа — рабочая сборка, зелёные тесты, коммит.
4. Если требование противоречиво или не хватает данных — **не выдумывай**. Останови работу, задай вопрос, зафиксируй допущение в `DECISIONS.md`.
5. Не хардкодь секреты. Всё через переменные окружения (§8).

### Что уже есть в проекте (стартовая точка)

**Каркас уже написан и лежит в репозитории. Агент начинает не с нуля.**

Что уже готово:
- Gradle-проект на Java 21 + Spring Boot, собирается (`./gradlew build`);
- подключение к Telegram через long polling, приём апдейтов;
- простейший обработчик: бот получает сообщение и отвечает на него (эхо/реплай) — то есть канал связи с Telegram уже проверен и работает;
- `Dockerfile` и `docker-compose.yml` в базовом виде.

Что агент обязан сделать с этим каркасом:
- **не переписывать его с нуля и не менять пакетную структуру без причины** — разобраться в существующем коде и расширять его;
- перед началом работы прочитать текущий код приёма апдейтов и в `DECISIONS.md` записать, что именно переиспользуется, а что заменяется и почему;
- существующий обработчик-реплай превратить в полноценный `UpdateHandler` + `CommandRouter` (§3), а не городить второй параллельный;
- если каркас в чём-то расходится с этой спекой (другая версия библиотеки, другой билд-инструмент) — приоритет у **фактического кода в репозитории**, спеку в этом месте адаптировать и отметить расхождение в `DECISIONS.md`.

Соответственно этапы 1 и 2 из §12 считаются частично закрытыми — их нужно только доработать до критериев приёмки, а не выполнять целиком.

---

## 1. Цель и границы

### Что делаем

Телеграм-бот, который живёт в супергруппе с включёнными **топиками (Forum Topics)**. Бот:

- отслеживает топики, название которых начинается с префикса `news-` (например `news-it`, `news-science`, `news-ai-safety`);
- по расписанию собирает свежие англоязычные новости по тематике из названия топика;
- публикует каждую новость отдельным сообщением **в соответствующий топик**;
- **автоматически подхватывает новый топик**, созданный пользователем: создали `news-biotech` → бот начал слать новости про биотех без перезапуска и без правок кода.

### Формат новости

Дата и время, заголовок, краткий пересказ (2–3 предложения), ссылка на оригинал.

### Чего НЕ делаем (non-goals для v1)

- перевод новостей на другие языки;
- личные (private) чаты и обычные группы без топиков — только форум-супергруппа;
- веб-интерфейс, админ-панель;
- работа с несколькими группами одновременно (архитектурно закладываем, но в v1 — одна группа);
- комментарии, реакции, дискуссии под новостями.

---

## 2. Ограничения Telegram Bot API ⚠️ ЧИТАТЬ ОБЯЗАТЕЛЬНО

**Главное ограничение: Bot API не умеет получать список топиков чата.**

В Bot API есть `createForumTopic`, `editForumTopic`, `closeForumTopic`, `deleteForumTopic` — но **нет метода вроде `getForumTopics`**. Получить «все топики группы» одним запросом невозможно. Список топиков с их названиями существует только в MTProto (клиентский API), для бота он недоступен.

**Вывод: бот обязан вести собственный реестр топиков в БД**, наполняемый из апдейтов.

### Как бот узнаёт о топиках

| Событие | Что приходит | Что делаем |
|---|---|---|
| Создан новый топик | `Message` с полем `forum_topic_created` (внутри — `name`), `message_thread_id` = id топика | Создаём/обновляем запись в реестре |
| Топик переименован | `Message` с полем `forum_topic_edited` (`name` может быть null, если менялась только иконка) | Обновляем имя, пересчитываем тематику |
| Топик закрыт / удалён | `forum_topic_closed`, `forum_topic_reopened` | Ставим/снимаем флаг `active` |
| Любое сообщение в топике | `message_thread_id`, `is_topic_message = true` | Имени топика тут НЕТ — только id |

### Что бот УМЕЕТ делать с топиками

В отличие от чтения списка, **создание и изменение топиков боту доступно**:

| Метод Bot API | Назначение | Что возвращает |
|---|---|---|
| `createForumTopic` | создать топик | объект `ForumTopic` с `message_thread_id`, `name`, `icon_color` |
| `editForumTopic` | переименовать / сменить иконку | `true` |
| `closeForumTopic` / `reopenForumTopic` | закрыть / открыть | `true` |
| `deleteForumTopic` | удалить топик вместе с сообщениями | `true` |
| `pinChatMessage` | закрепить сообщение (в т.ч. внутри топика) | `true` |

Ключевое следствие: **если топик создаёт сам бот, он сразу получает `message_thread_id` в ответе метода** и записывает топик в реестр немедленно — не дожидаясь апдейта `forum_topic_created` и не завися от него. Это самый надёжный путь заведения топиков, и он должен быть основным (§9a).

Требования к правам: бот — админ с `can_manage_topics`; для закрепления приветствия дополнительно `can_pin_messages`.

Ограничения `createForumTopic`:
- `name` — от 1 до 128 символов;
- `icon_color` — только одно из фиксированных значений: `0x6FB9F0`, `0xFFD67E`, `0xCB86DB`, `0x8EEE98`, `0xFF93B2`, `0xFB6F5F`. Произвольный цвет вызовет ошибку;
- `icon_custom_emoji_id` — только эмодзи из набора, разрешённого для топиков; при сомнениях не передавать вовсе;
- Telegram **не запрещает создавать топики с одинаковыми именами**, дубликаты придётся отсекать самому (§9a).

### Топики, созданные ДО добавления бота

Бот их не увидит вообще. Решение — команда ручной привязки:

```
/bind news-it      ← отправляется ВНУТРИ нужного топика
```

Бот берёт `message_thread_id` из этого сообщения и переданное имя, пишет в реестр. Если имя не передано — берёт его из `/bind` без аргументов и отвечает подсказкой.

В README обязательно написать: **добавьте бота в группу ДО создания новостных топиков, иначе используйте `/bind`.**

### Настройки, без которых не заработает

- В BotFather: `/setprivacy` → **Disable** (иначе бот не увидит сообщения и команды в группе).
- Бот должен быть **администратором** группы с правами `Manage Topics` и правом отправки сообщений.
- Отправка в топик: параметр `message_thread_id` в `sendMessage`. Без него сообщение уйдёт в General.
- `General`-топик не имеет `message_thread_id` — обрабатывать как отсутствие треда.

### Лимиты Telegram

- ~**20 сообщений в минуту в одну группу** (лимит на чат, не на топик — топики одной группы делят квоту).
- ~30 сообщений в секунду глобально.
- При превышении прилетает `429 Too Many Requests` с `retry_after`.

Требования:
- глобальная очередь исходящих сообщений с троттлингом (по умолчанию 1 сообщение раз в 3.5 сек на чат);
- обработка `429` с уважением к `retry_after` + exponential backoff;
- лимит `maxItemsPerTopicPerRun` (по умолчанию 5), чтобы один топик не забил очередь.

---

## 3. Архитектура

Модульный монолит, Spring Boot. Пакеты:

```
com.example.newsbot
├── NewsBotApplication.java
├── telegram/            # приём апдейтов, отправка сообщений, троттлинг
│   ├── UpdateHandler.java
│   ├── TelegramSender.java        # очередь + rate limit + retry на 429
│   ├── command/                   # /start /bind /unbind /status /now /help
│   └── dto/
├── topic/               # реестр топиков
│   ├── TopicRegistryService.java
│   ├── TopicProvisioningService.java   # СОЗДАНИЕ топиков ботом (§9a)
│   ├── TopicBootstrapRunner.java       # авто-создание топиков на старте
│   ├── WelcomeMessageFormatter.java    # приветствие в новый топик
│   ├── TopicEntity.java
│   ├── TopicRepository.java
│   └── TopicSlugParser.java       # "news-ai-safety" -> slug "ai-safety" -> query "ai safety"
├── news/
│   ├── NewsProvider.java          # интерфейс
│   ├── rss/RssNewsProvider.java   # Rome
│   ├── googlenews/GoogleNewsRssProvider.java
│   ├── NewsAggregator.java        # объединяет провайдеры, сортирует, дедуп
│   └── model/NewsItem.java
├── summary/
│   ├── Summarizer.java            # интерфейс
│   ├── ExtractiveSummarizer.java  # без LLM, дефолт
│   └── LlmSummarizer.java         # опционально, §11
├── dedup/
│   ├── SeenArticleService.java
│   └── UrlCanonicalizer.java
├── schedule/
│   └── NewsPollingJob.java        # @Scheduled, обходит активные топики
└── config/
    ├── BotProperties.java
    └── TopicPresetsConfig.java
```

### Поток данных

```
[Telegram] --update--> UpdateHandler --> TopicRegistryService --> [DB: topics]
                                    \--> CommandRouter

[Scheduler] --каждые N мин--> для каждого активного топика:
      TopicSlugParser (news-it -> query)
   -> NewsAggregator -> NewsProvider(s) -> List<NewsItem>
   -> SeenArticleService (отсеять уже отправленные)
   -> Summarizer (краткий пересказ)
   -> MessageFormatter (HTML)
   -> TelegramSender (очередь, троттлинг) -> [Telegram топик]
   -> SeenArticleService.markSent()
```

### Ключевые интерфейсы

```java
public interface NewsProvider {
    String name();
    boolean supports(TopicQuery query);
    List<NewsItem> fetch(TopicQuery query, Instant since, int limit);
}

public interface Summarizer {
    String summarize(NewsItem item);   // 2–3 предложения, английский
}

public record NewsItem(
    String externalId,      // guid из RSS или canonical URL
    String title,
    String url,
    String sourceName,
    Instant publishedAt,
    String rawDescription,  // HTML из фида, до очистки
    String imageUrl         // nullable
) {}
```

---

## 4. Модель данных (PostgreSQL, миграции через Flyway)

**topics**
| колонка | тип | описание |
|---|---|---|
| id | bigserial PK | |
| chat_id | bigint | id супергруппы |
| thread_id | bigint | `message_thread_id` топика |
| raw_name | text | `news-it` |
| slug | text | `it` |
| query | text | итоговый поисковый запрос |
| active | boolean | false если топик закрыт/удалён/не `news-*` |
| created_by_bot | boolean | топик заведён через `TopicProvisioningService` |
| welcome_message_id | bigint | id приветственного сообщения, nullable |
| last_polled_at | timestamptz | |
| created_at, updated_at | timestamptz | |

Уникальный индекс: `(chat_id, thread_id)`.
Дополнительный частичный уникальный индекс: `(chat_id, slug) WHERE active` — защита от двух активных топиков с одной тематикой.

**seen_articles**
| колонка | тип |
|---|---|
| id | bigserial PK |
| topic_id | bigint FK → topics |
| fingerprint | text — sha256 от canonical URL |
| title_hash | text — sha256 от нормализованного заголовка |
| url | text |
| sent_at | timestamptz |

Уникальный индекс: `(topic_id, fingerprint)`. Отдельный индекс по `title_hash` — одна и та же новость приходит с разных агрегаторов с разными URL.

Ретеншен: чистить записи старше 30 дней ежедневной задачей.

---

## 5. Тематика из названия топика

```
"news-it"          -> slug "it"          -> query из пресета
"news-ai-safety"   -> slug "ai-safety"   -> query "ai safety"
"news-space"       -> slug "space"       -> query "space"
"News-IT"          -> регистронезависимо -> slug "it"
"general"          -> не news-* -> игнор
```

Алгоритм `TopicSlugParser`:
1. Нормализовать имя: trim, lowercase, убрать эмодзи.
2. Если не начинается с `news-` (или `news_`) → топик не наш, `active = false`.
3. Slug = остаток после префикса.
4. Есть пресет в `topic-presets.yml` → взять его feeds + query. Нет → fallback: query = slug с заменой `-` на пробел, провайдер — Google News Search RSS.

Файл `src/main/resources/topic-presets.yml`:

```yaml
presets:
  it:
    query: "technology OR software OR programming"
    feeds:
      - https://feeds.arstechnica.com/arstechnica/technology-lab
      - https://techcrunch.com/feed/
      - https://www.theverge.com/rss/index.xml
  science:
    query: "science research"
    feeds:
      - https://www.sciencedaily.com/rss/all.xml
      - https://phys.org/rss-feed/
  ai:
    query: "artificial intelligence"
    feeds:
      - https://www.technologyreview.com/topic/artificial-intelligence/feed
```

**Важно:** пресеты — это оптимизация качества, а не условие работы. Топик с неизвестной тематикой обязан работать через fallback. Это и есть главная фича «создал топик — получил новости».

---

## 6. Источники новостей

### v1: только RSS/Atom — без API-ключей

**Провайдер 1 — `RssNewsProvider`**: тянет фиды из пресета. Библиотека **Rome** (`com.rometools:rome`).

**Провайдер 2 — `GoogleNewsRssProvider`** (fallback для любой тематики):

```
https://news.google.com/rss/search?q={URL-encoded query}&hl=en-US&gl=US&ceid=US:en
```

Возвращает англоязычную выдачу по произвольному запросу. Даёт «любую тематику из коробки».

Особенности, которые надо обработать:
- ссылки в Google News RSS — редиректные (`news.google.com/rss/articles/...`). Резолвить через HTTP HEAD с `followRedirects`, брать финальный URL. Если не резолвится — слать как есть, не падать.
- заголовок часто содержит хвост ` - Source Name`. Отрезать, имя источника класть в `sourceName`.

### Требования к HTTP-слою

- Java `HttpClient` (или Spring `RestClient`), timeout 10 сек;
- `User-Agent` осмысленный, не дефолтный;
- retry 2 раза с backoff на 5xx и таймауты;
- падение одного фида **не должно** ронять весь цикл — логируем warn и идём дальше;
- параллельная выборка фидов через `ExecutorService` (virtual threads Java 21), но не больше 8 одновременно.

### Отбор новостей

- берём только `publishedAt > max(last_polled_at, now - 24h)`;
- сортируем по `publishedAt` desc;
- отсекаем дубликаты (§4);
- берём не больше `maxItemsPerTopicPerRun`.

---

## 7. Формат сообщения

`parse_mode = HTML` (**не MarkdownV2** — там ад с экранированием). Экранировать `& < >` в пользовательском тексте.

Шаблон:

```html
🗞 <b>{title}</b>

{summary}

🕒 {2026-09-06 14:32 UTC}  •  📰 {sourceName}
🔗 <a href="{url}">Read full article</a>
```

- `disable_web_page_preview = false` для первой новости, `true` для остальных в пачке (иначе топик превращается в кашу из превью) — вынести в конфиг `linkPreview: FIRST_ONLY | ALWAYS | NEVER`;
- время в UTC, ISO-подобный формат `yyyy-MM-dd HH:mm 'UTC'`, таймзона настраивается;
- `title` обрезать до 200 символов, `summary` до 500;
- если сообщение всё равно > 4096 символов — жёстко обрезать с `…`.

---

## 8. Конфигурация

Всё через env, значения по умолчанию — в `application.yml`.

| Переменная | Обяз. | Дефолт | Описание |
|---|---|---|---|
| `BOT_TOKEN` | да | — | токен от BotFather |
| `BOT_USERNAME` | да | — | username бота |
| `ALLOWED_CHAT_IDS` | да | — | id групп через запятую; апдейты из других игнорируются |
| `ADMIN_USER_IDS` | нет | — | кто может дёргать `/now`, `/bind` |
| `POLL_INTERVAL_MINUTES` | нет | `30` | период опроса источников |
| `MAX_ITEMS_PER_TOPIC` | нет | `5` | максимум новостей за цикл |
| `NEWS_LOOKBACK_HOURS` | нет | `24` | «свежесть» новости |
| `TOPIC_PREFIX` | нет | `news-` | префикс отслеживаемых топиков |
| `BOOTSTRAP_TOPICS` | нет | пусто | тематики через запятую, создаются ботом при старте (§9a, способ 2) |
| `WELCOME_PIN` | нет | `true` | закреплять ли приветствие в топике |
| `SEND_RATE_PER_MINUTE` | нет | `17` | троттлинг (запас к лимиту 20) |
| `SUMMARIZER` | нет | `extractive` | `extractive` \| `llm` |
| `LLM_API_KEY` / `LLM_MODEL` | нет | — | для `SUMMARIZER=llm` |
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | да | — | Postgres |
| `LOG_LEVEL` | нет | `INFO` | |

`ALLOWED_CHAT_IDS` — обязательная защита: бота могут добавить в чужой чат.

---

## 9. Команды бота

| Команда | Кто | Где | Действие |
|---|---|---|---|
| `/start`, `/help` | все | любой топик | краткая инструкция |
| `/newtopic <slug>` | админ | любой топик | **создать** новостной топик `news-<slug>` и написать в него приветствие (§9a) |
| `/newtopics <s1> <s2> …` | админ | любой топик | пакетное создание нескольких топиков за раз |
| `/presets` | админ | любой | показать доступные пресеты тематик с пометкой, какие уже созданы |
| `/bind [name]` | админ | внутри топика | привязать существующий топик; `name` по умолчанию — из аргумента, иначе просит указать |
| `/unbind` | админ | внутри топика | `active = false` |
| `/renametopic <slug>` | админ | внутри топика | `editForumTopic` + обновление реестра + правка приветствия |
| `/closetopic` | админ | внутри топика | `closeForumTopic` + `active = false` |
| `/status` | админ | любой | список активных топиков, время последнего опроса, счётчики |
| `/now` | админ | внутри топика | внеочередной опрос этого топика |
| `/topics` | админ | любой | дамп реестра из БД |

Требования: любая команда отвечает **в тот же топик** (`message_thread_id` из входящего). Неизвестная команда — игнор, без спама.

---

## 9a. Создание топиков ботом

Бот должен уметь заводить новостные топики сам. Это надёжнее ручного создания: `createForumTopic` сразу возвращает `message_thread_id`, и топик попадает в реестр без гонки с апдейтами.

### Единая точка входа

Все способы создания идут через один сервис — никакой дублирующей логики в командах:

```java
public interface TopicProvisioningService {

    /** Создать один новостной топик по slug ("it" -> топик "news-it"). */
    ProvisionResult provision(long chatId, String slug);

    /** Пакетное создание. Не прерывается на первой ошибке. */
    List<ProvisionResult> provisionAll(long chatId, List<String> slugs);

    /** Переименовать существующий топик и синхронизировать реестр. */
    ProvisionResult rename(long chatId, long threadId, String newSlug);

    /** Закрыть топик и деактивировать его в реестре. */
    void close(long chatId, long threadId);
}

public record ProvisionResult(
    String slug,
    Status status,          // CREATED | ALREADY_EXISTS | INVALID_SLUG | FAILED
    Long threadId,          // nullable
    String message          // человекочитаемое пояснение для ответа в чат
) {
    public enum Status { CREATED, ALREADY_EXISTS, INVALID_SLUG, FAILED }
}
```

### Алгоритм `provision(chatId, slug)`

1. **Нормализовать slug:** trim, lowercase, пробелы и `_` → `-`, схлопнуть повторяющиеся дефисы, срезать дефисы по краям.
2. **Валидировать:** разрешены `a–z`, `0–9`, `-`; длина slug 2–100 символов (с учётом префикса имя должно уложиться в 128). Если пользователь ввёл `news-it` — префикс не дублировать, срезать его. Невалидно → `INVALID_SLUG`, ничего не создаём.
3. **Проверить реестр:** есть активный топик с таким `(chat_id, slug)` → `ALREADY_EXISTS`, вернуть ссылку на существующий тред, повторно не создавать.
4. **`createForumTopic`** с именем `TOPIC_PREFIX + slug` и `icon_color`, выбранным детерминированно: `PALETTE[abs(slug.hashCode()) % 6]` — одинаковая тематика всегда одного цвета, разные визуально различимы.
5. **Записать в реестр** сразу: `thread_id` из ответа, `raw_name`, `slug`, `query` (из пресета или fallback), `active = true`, `created_by_bot = true`.
6. **Отправить приветственное сообщение** в новый тред (шаблон ниже), сохранить его `message_id` в `welcome_message_id`.
7. **Закрепить** приветствие через `pinChatMessage` с `message_thread_id` и `disable_notification = true`. Если прав нет — залогировать warn и **не считать это ошибкой**, топик уже рабочий.
8. Вернуть `CREATED`.

### Приветственное сообщение

Отправляется первым сообщением в топик, `parse_mode = HTML`:

```html
📌 <b>News topic: {slug_display}</b>

Этот топик автоматически наполняется новостями по теме «<b>{slug_display}</b>».

• Источники: {sources_summary}
• Язык новостей: English
• Интервал обновления: каждые {interval} мин
• Не более {maxItems} новостей за раз

Формат каждой публикации: заголовок, краткий пересказ, дата и время (UTC), ссылка на оригинал.

Чтобы завести ещё одну тему — создайте топик с именем <code>news-&lt;тема&gt;</code>
или отправьте команду <code>/newtopic &lt;тема&gt;</code>.
```

Где:
- `slug_display` — slug с дефисами, заменёнными на пробелы, Title Case: `ai-safety` → `Ai Safety`;
- `sources_summary` — если тематика из пресета, перечислить домены фидов (максимум 3, дальше `и ещё N`); если fallback — написать «поиск по всем англоязычным источникам»;
- текст приветствия вынести в `messages.yml` / `MessageSource`, **не хардкодить в коде**.

При `/renametopic` приветствие не пересоздаётся, а редактируется через `editMessageText` по сохранённому `welcome_message_id`. Если сообщение удалено (ошибка `message to edit not found`) — отправить новое и обновить id.

### Три способа создания — все через один сервис

**Способ 1 — команда в чате (основной):**
```
/newtopic biotech          → создаётся топик "news-biotech" + приветствие
/newtopics it science ai   → три топика за один вызов
```
Для пакетного режима: между вызовами `createForumTopic` пауза ≥1 сек (лимит Telegram), ответ одним сводным сообщением — что создано, что уже существовало, что упало.

**Способ 2 — автопровижининг при старте:**
Переменная `BOOTSTRAP_TOPICS=it,science,ai`. `TopicBootstrapRunner` при старте приложения прогоняет `provisionAll` для каждого чата из `ALLOWED_CHAT_IDS`.
Обязательно **идемпотентно**: перезапуск контейнера не должен плодить дубликаты — шаг 3 алгоритма это гарантирует. Ошибки логируются, приложение не падает.

**Способ 3 — ручное создание пользователем (уже описано в §2):**
Пользователь сам создаёт топик `news-*` → приходит `forum_topic_created` → `TopicRegistryService` регистрирует его → **и тоже отправляет приветственное сообщение**, тем же `WelcomeMessageFormatter`. Поведение для пользователя одинаковое независимо от способа создания.

### Идемпотентность — критично

Когда топик создаёт бот, Telegram **всё равно пришлёт апдейт** `forum_topic_created` по этому же топику. Без защиты бот зарегистрирует его повторно и отправит второе приветствие.

Требования:
- запись в реестр — строго `upsert` по `(chat_id, thread_id)`;
- приветствие отправляется, только если `welcome_message_id IS NULL`;
- проверка и вставка внутри одной транзакции.

Покрыть тестом: `provision()` + последующий апдейт `forum_topic_created` с тем же `thread_id` = ровно одна запись в БД и ровно одно приветствие.

---

## 10. Docker

### Dockerfile — multi-stage

```dockerfile
# --- build ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies || true    # кеш слоя зависимостей
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# --- runtime ---
FROM eclipse-temurin:21-jre
RUN useradd -r -u 1001 -m appuser
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
USER appuser
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
```

### docker-compose.yml

```yaml
services:
  db:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: newsbot
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER} -d newsbot"]
      interval: 10s
      retries: 5
    restart: unless-stopped

  bot:
    build: .
    env_file: .env
    environment:
      DB_URL: jdbc:postgresql://db:5432/newsbot
    depends_on:
      db: { condition: service_healthy }
    ports: ["8080:8080"]
    restart: unless-stopped

volumes:
  pgdata:
```

Дополнительно:
- `.env.example` в репозитории со всеми ключами и пустыми значениями; `.env` — в `.gitignore`;
- `make up` / `make logs` / `make down` — или `README` с готовыми командами;
- Spring Boot Actuator: `/actuator/health` (liveness + кастомный индикатор «последний успешный опрос был < 2×интервала назад»).

**Проверка приёмки Docker:** на чистой машине `cp .env.example .env` → вписать 3 значения → `docker compose up -d` → бот работает. Никаких ручных шагов больше.

---

## 11. Опциональный LLM-агент для пересказов

Дефолт — `ExtractiveSummarizer`: чистит HTML из `description` фида (Jsoup), берёт первые 2–3 предложения, обрезает. Работает без ключей, без денег, без сети сверх RSS.

`LlmSummarizer` включается через `SUMMARIZER=llm`:

- вход: заголовок + описание из фида (**не** качаем полный текст статьи — это отдельная задача и юридический риск);
- промпт: «Summarize this news item in 2–3 factual sentences in English. No opinions, no speculation, no marketing language. If the input is insufficient, return the description as is.»;
- жёсткие требования: таймаут 15 сек, максимум 1 ретрай, **фолбэк на `ExtractiveSummarizer` при любой ошибке**. Бот не должен молчать из-за недоступного LLM;
- кеш пересказов по `fingerprint` в БД — не платить дважды за ту же статью;
- лимит расхода: `LLM_MAX_CALLS_PER_HOUR`, при превышении — фолбэк.

В Docker дополнительный контейнер не нужен: это HTTP-клиент внутри того же приложения. Ключ передаётся через `.env`.

---

## 12. План работ по этапам

Каждый этап заканчивается: рабочая сборка + зелёные тесты + коммит + отметка в `PROGRESS.md`.

**Этап 1 — Доработка каркаса** *(частично готово, см. §0)*
Разобрать существующий код. Добавить: Postgres в compose, Flyway, `/actuator/health`, конфигурацию через `BotProperties`, фильтр по `ALLOWED_CHAT_IDS`.
✅ Приёмка: `docker compose up` поднимает бота и БД, health = UP, миграции применились.

**Этап 2 — Command router** *(частично готово, см. §0)*
Существующий обработчик-реплай превратить в `UpdateHandler` + `CommandRouter`. Реализовать `/start`, `/help`. Ответ строго в тот же `message_thread_id`.
✅ Приёмка: `/start` в топике получает ответ в этом же топике, а не в General.

**Этап 3 — Реестр топиков**
Таблица `topics`, обработка `forum_topic_created` / `forum_topic_edited` / `forum_topic_closed`, парсер slug, команды `/bind`, `/unbind`, `/topics`.
✅ Приёмка: создаю топик `news-test` → он появляется в `/topics` со slug `test`; переименовываю → запись обновилась.

**Этап 3a — Создание топиков ботом (§9a)**
`TopicProvisioningService`, `WelcomeMessageFormatter`, команды `/newtopic`, `/newtopics`, `/renametopic`, `/closetopic`, `/presets`, `TopicBootstrapRunner` + `BOOTSTRAP_TOPICS`.
✅ Приёмка: `/newtopic biotech` → в группе появляется топик `news-biotech` с закреплённым приветствием; повторный `/newtopic biotech` отвечает «уже существует» и ничего не создаёт; перезапуск контейнера с `BOOTSTRAP_TOPICS` не плодит дубликаты и не шлёт второе приветствие.

**Этап 4 — Получение новостей**
`NewsProvider`, RSS через Rome, Google News fallback, пресеты, резолв редиректов.
✅ Приёмка: unit-тест на замоканном фиде отдаёт корректный `List<NewsItem>`.

**Этап 5 — Дедупликация и форматирование**
`seen_articles`, canonical URL, `ExtractiveSummarizer`, HTML-шаблон сообщения.
✅ Приёмка: повторный прогон на тех же данных не отправляет ничего.

**Этап 6 — Планировщик и отправка**
`@Scheduled`, очередь с троттлингом, retry на 429, `/now`, `/status`.
✅ Приёмка: в топик `news-it` реально приходят новости в нужном формате; создание топика `news-space` через 1 цикл даёт новости про космос **без перезапуска**.

**Этап 7 — Полировка**
README, `.env.example`, graceful shutdown, ретеншен `seen_articles`, структурные логи, опциональный `LlmSummarizer`.

---

## 13. Тестирование

- **JUnit 5 + AssertJ** — обязательно.
- **Unit:** `TopicSlugParser` (включая эмодзи, регистр, `news_`, не-news имена), `UrlCanonicalizer`, `MessageFormatter` (экранирование HTML, обрезка), `ExtractiveSummarizer`.
- **Фиды:** сохранить 2–3 реальных XML в `src/test/resources/feeds/`, парсить их — без сети в тестах.
- **HTTP:** MockWebServer / WireMock для провайдеров, включая сценарии 500, таймаут, битый XML.
- **Интеграционные:** Testcontainers + Postgres для репозиториев и дедупликации.
- **Telegram-слой:** мок `TelegramSender`, проверять что вызван с правильным `message_thread_id`.
- Сеть в тестах запрещена. CI-прогон `./gradlew test` должен работать офлайн.

---

## 14. Работа с кодовым агентом

### Файлы в корне репозитория

**`AGENTS.md`** (или `CLAUDE.md`) — короткая памятка, которую агент читает при каждом запуске:

```markdown
# Rules
- Java 21, Spring Boot 3.x, Gradle. Build: `./gradlew build`. Tests: `./gradlew test`.
- Full spec: docs/SPEC.md. Read it before changing behaviour.
- Never commit secrets. Never hardcode chat ids or tokens.
- Telegram Bot API has NO method to list forum topics — the registry in DB is the source of truth.
- Every change must keep `docker compose up` working.
- Small commits, conventional commits format.
- If a requirement is ambiguous: stop, ask, log the assumption in DECISIONS.md.
```

**`docs/SPEC.md`** — этот документ.
**`PROGRESS.md`** — чеклист этапов из §12, агент отмечает выполненное.
**`DECISIONS.md`** — журнал принятых решений и допущений.

### Правила взаимодействия

- Один этап §12 = одна сессия/задача. Не давать «сделай всё» одним промптом.
- В конце этапа агент показывает: что сделал, какие тесты добавил, что осталось.
- Запрет на «заглушки, которые молча возвращают пустоту» — либо реализация, либо явное `TODO` с `UnsupportedOperationException` и записью в `PROGRESS.md`.
- Запрет на изменение уже принятых миграций Flyway — только новые файлы.

### Запуск агента в Docker

Изолируем агента от хоста: у него доступ только к репозиторию.

`.devcontainer/Dockerfile`:

```dockerfile
FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends \
      git curl ca-certificates jq docker.io \
    && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 1000 agent
USER agent
WORKDIR /workspace
```

Запуск:

```bash
docker run --rm -it \
  -v "$PWD":/workspace \
  -v /var/run/docker.sock:/var/run/docker.sock \   # если агенту нужно поднимать compose
  --env-file .env.agent \
  -w /workspace \
  newsbot-agent bash
```

Правила безопасности:
- **боевой `BOT_TOKEN` агенту не даём.** Заводим отдельного тестового бота и отдельную тестовую группу для разработки;
- `.env` не монтируем внутрь агент-контейнера, только `.env.agent` с тестовыми значениями;
- проброс docker.sock даёт агенту root на хосте — если это неприемлемо, не пробрасываем, а `docker compose` запускаем вручную снаружи;
- ограничить исходящую сеть агенту (нужны только: репозитории зависимостей, github, RSS-хосты).

---

## 15. Подводные камни — контрольный список

- [ ] Нет `getForumTopics` — реестр в БД, `/bind` для старых топиков (§2).
- [ ] После `createForumTopic` придёт ещё и апдейт `forum_topic_created` — upsert по `(chat_id, thread_id)`, иначе дубль записи и второе приветствие (§9a).
- [ ] Приветствие отправляется только при `welcome_message_id IS NULL`.
- [ ] `BOOTSTRAP_TOPICS` идемпотентен: рестарт контейнера не создаёт топики заново.
- [ ] `icon_color` — только 6 разрешённых значений, произвольный цвет = ошибка API.
- [ ] Имя топика ≤ 128 символов **с учётом префикса** `news-`.
- [ ] `/newtopic news-it` — префикс не задваивать, получить `news-it`, а не `news-news-it`.
- [ ] Пакетное создание — пауза между вызовами `createForumTopic`, не прерываться на первой ошибке.
- [ ] Нет прав `can_pin_messages` — приветствие всё равно отправлено, топик рабочий, только warn в лог.
- [ ] Privacy mode в BotFather выключен, бот — админ с `can_manage_topics`.
- [ ] `message_thread_id` не забыт в `sendMessage`, иначе всё летит в General.
- [ ] `forum_topic_edited.name` бывает `null` (меняли только иконку) — не затирать имя.
- [ ] Rate limit 20 msg/min на группу, а не на топик. Троттлинг обязателен.
- [ ] `429` с `retry_after` обрабатывается, не игнорируется.
- [ ] Ссылки Google News редиректные — резолвить, но не падать если не вышло.
- [ ] Дедуп по canonical URL **и** по нормализованному заголовку.
- [ ] `publishedAt` в RSS бывает пустым или в кривом формате — фолбэк на `now`, не бросать исключение.
- [ ] HTML-теги из `description` фида чистить (Jsoup), иначе `parse_mode=HTML` сломается.
- [ ] Экранировать `& < >` в заголовках.
- [ ] Один упавший фид не роняет цикл.
- [ ] На первом запуске нового топика не вывалить 50 старых новостей — ограничить `NEWS_LOOKBACK_HOURS` и `MAX_ITEMS_PER_TOPIC`.
- [ ] `ALLOWED_CHAT_IDS` — иначе бота добавят в чужой чат.
- [ ] Таймзона контейнера = UTC явно.
- [ ] Graceful shutdown: дослать очередь или сохранить её, не терять `markSent`.

---

## 16. Definition of Done

Проект считается готовым, когда:

1. `git clone` → `cp .env.example .env` → заполнить `BOT_TOKEN`, `BOT_USERNAME`, `ALLOWED_CHAT_IDS`, пароль БД → `docker compose up -d` → бот работает.
2. Создание топика `news-<любая-тема>` в группе приводит к появлению там англоязычных новостей в течение одного цикла опроса, **без перезапуска и без правок кода**.
3. Команда `/newtopic <тема>` создаёт топик силами бота и публикует в нём закреплённое приветствие с описанием тематики. Повторный вызов не создаёт дубликат.
4. Приветственное сообщение появляется в топике **независимо от способа создания** — через `/newtopic`, через `BOOTSTRAP_TOPICS` или при ручном создании топика пользователем.
5. Формат новостного сообщения соответствует §7.
6. Повторные новости не дублируются между циклами.
7. `./gradlew test` зелёный и работает без сети.
8. README описывает: настройку BotFather, права бота (`can_manage_topics`, `can_pin_messages`), оба способа заведения топиков, все переменные окружения, команды, troubleshooting.
9. В репозитории нет секретов.
