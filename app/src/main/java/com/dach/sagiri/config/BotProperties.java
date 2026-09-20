package com.dach.sagiri.config;

import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every knob of the bot, bound from environment variables (see {@code .env.example}).
 * Defaults live in {@code application.yaml}; nothing here may be hardcoded to a
 * concrete chat, token or key.
 */
@ConfigurationProperties(prefix = "bot")
public class BotProperties {

    /** Whether a link preview is shown under a news message. */
    public enum LinkPreview {
        /** Preview only for the first message of a batch, the rest are muted. */
        FIRST_ONLY,
        ALWAYS,
        NEVER
    }

    private String token = "";
    private String username = "";
    private List<Long> allowedChatIds = List.of();
    private List<Long> adminUserIds = List.of();
    private int pollIntervalMinutes = 30;
    private int maxItemsPerTopic = 5;
    private int newsLookbackHours = 24;
    private String topicPrefix = "news-";
    private List<String> bootstrapTopics = List.of();
    private boolean welcomePin = true;

    /** Let the model read an ambiguous topic name once and say what it is about. */
    private boolean topicProfile = true;
    private int sendRatePerMinute = 17;
    private String summarizer = "extractive";
    private LinkPreview linkPreview = LinkPreview.FIRST_ONLY;
    private String timezone = "UTC";
    private int seenRetentionDays = 30;
    private int shutdownDrainSeconds = 20;
    private String googleNewsBaseUrl = "https://news.google.com/rss/search";
    /** Turn a Google News stub into the publisher's own URL; see DECISIONS.md 20. */
    private boolean googleNewsDecodeLinks = true;
    private Http http = new Http();
    private Filter filter = new Filter();
    private Llm llm = new Llm();
    private Ollama ollama = new Ollama();
    private ArticleContext articleContext = new ArticleContext();

    /** No ADMIN_USER_IDS configured means every member of an allowed chat is an admin. */
    public boolean isAdmin(Long userId) {
        return adminUserIds.isEmpty() || (userId != null && adminUserIds.contains(userId));
    }

    /** Updates from any other chat are dropped: the bot can be added to a foreign group. */
    public boolean isAllowedChat(long chatId) {
        return allowedChatIds.contains(chatId);
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }

    public String topicName(String slug) {
        return topicPrefix + slug;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public List<Long> getAllowedChatIds() {
        return allowedChatIds;
    }

    public void setAllowedChatIds(List<Long> allowedChatIds) {
        this.allowedChatIds = allowedChatIds;
    }

    public List<Long> getAdminUserIds() {
        return adminUserIds;
    }

    public void setAdminUserIds(List<Long> adminUserIds) {
        this.adminUserIds = adminUserIds;
    }

    public int getPollIntervalMinutes() {
        return pollIntervalMinutes;
    }

    public void setPollIntervalMinutes(int pollIntervalMinutes) {
        this.pollIntervalMinutes = pollIntervalMinutes;
    }

    public int getMaxItemsPerTopic() {
        return maxItemsPerTopic;
    }

    public void setMaxItemsPerTopic(int maxItemsPerTopic) {
        this.maxItemsPerTopic = maxItemsPerTopic;
    }

    public int getNewsLookbackHours() {
        return newsLookbackHours;
    }

    public void setNewsLookbackHours(int newsLookbackHours) {
        this.newsLookbackHours = newsLookbackHours;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    public List<String> getBootstrapTopics() {
        return bootstrapTopics;
    }

    public void setBootstrapTopics(List<String> bootstrapTopics) {
        this.bootstrapTopics = bootstrapTopics;
    }

    public boolean isTopicProfile() {
        return topicProfile;
    }

    public void setTopicProfile(boolean topicProfile) {
        this.topicProfile = topicProfile;
    }

    public boolean isWelcomePin() {
        return welcomePin;
    }

    public void setWelcomePin(boolean welcomePin) {
        this.welcomePin = welcomePin;
    }

    public int getSendRatePerMinute() {
        return sendRatePerMinute;
    }

    public void setSendRatePerMinute(int sendRatePerMinute) {
        this.sendRatePerMinute = sendRatePerMinute;
    }

    public String getSummarizer() {
        return summarizer;
    }

    public void setSummarizer(String summarizer) {
        this.summarizer = summarizer;
    }

    public LinkPreview getLinkPreview() {
        return linkPreview;
    }

    public void setLinkPreview(LinkPreview linkPreview) {
        this.linkPreview = linkPreview;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public int getSeenRetentionDays() {
        return seenRetentionDays;
    }

    public void setSeenRetentionDays(int seenRetentionDays) {
        this.seenRetentionDays = seenRetentionDays;
    }

    public int getShutdownDrainSeconds() {
        return shutdownDrainSeconds;
    }

    public void setShutdownDrainSeconds(int shutdownDrainSeconds) {
        this.shutdownDrainSeconds = shutdownDrainSeconds;
    }

    public String getGoogleNewsBaseUrl() {
        return googleNewsBaseUrl;
    }

    public void setGoogleNewsBaseUrl(String googleNewsBaseUrl) {
        this.googleNewsBaseUrl = googleNewsBaseUrl;
    }

    public boolean isGoogleNewsDecodeLinks() {
        return googleNewsDecodeLinks;
    }

    public void setGoogleNewsDecodeLinks(boolean googleNewsDecodeLinks) {
        this.googleNewsDecodeLinks = googleNewsDecodeLinks;
    }

    public Filter getFilter() {
        return filter;
    }

    public void setFilter(Filter filter) {
        this.filter = filter;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = llm;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public void setOllama(Ollama ollama) {
        this.ollama = ollama;
    }

    public ArticleContext getArticleContext() {
        return articleContext;
    }

    public void setArticleContext(ArticleContext articleContext) {
        this.articleContext = articleContext;
    }

    /** Per-topic news filters, edited at runtime with {@code /filter}. */
    public static class Filter {

        /** Whether the model also judges an article by meaning, not only by word. */
        private boolean aiCheck = true;
        private int maxTermsPerTopic = 30;

        public boolean isAiCheck() {
            return aiCheck;
        }

        public void setAiCheck(boolean aiCheck) {
            this.aiCheck = aiCheck;
        }

        public int getMaxTermsPerTopic() {
            return maxTermsPerTopic;
        }

        public void setMaxTermsPerTopic(int maxTermsPerTopic) {
            this.maxTermsPerTopic = maxTermsPerTopic;
        }
    }

    /** Settings of the outbound HTTP client used to pull feeds. */
    public static class Http {

        private int timeoutSeconds = 10;
        private int retries = 2;
        private int maxParallelFeeds = 8;
        private String userAgent = "JournalistNewsBot/1.0";

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getRetries() {
            return retries;
        }

        public void setRetries(int retries) {
            this.retries = retries;
        }

        public int getMaxParallelFeeds() {
            return maxParallelFeeds;
        }

        public void setMaxParallelFeeds(int maxParallelFeeds) {
            this.maxParallelFeeds = maxParallelFeeds;
        }

        public String getUserAgent() {
            return userAgent;
        }

        public void setUserAgent(String userAgent) {
            this.userAgent = userAgent;
        }
    }

    /** Optional LLM summariser, see spec section 11. Disabled unless SUMMARIZER=llm. */
    public static class Llm {

        private String apiKey = "";
        private String model = "claude-opus-5";
        private String baseUrl = "";
        private int maxCallsPerHour = 100;
        private int timeoutSeconds = 15;
        private int maxTokens = 400;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getMaxCallsPerHour() {
            return maxCallsPerHour;
        }

        public void setMaxCallsPerHour(int maxCallsPerHour) {
            this.maxCallsPerHour = maxCallsPerHour;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    /**
     * Local Ollama server, started next to the bot by docker compose. This is the
     * default summariser: no API key, no per-article cost, nothing leaves the
     * compose network.
     */
    public static class Ollama {

        private String baseUrl = "http://ollama:11434";
        private String model = "llama3.2:3b";
        /** A small model on CPU is slow; the polling job can afford to wait. */
        private int timeoutSeconds = 120;
        private int numPredict = 220;
        private double temperature = 0.2;
        /** Zero means unlimited — a local model costs nothing per call. */
        private int maxCallsPerHour = 0;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getNumPredict() {
            return numPredict;
        }

        public void setNumPredict(int numPredict) {
            this.numPredict = numPredict;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxCallsPerHour() {
            return maxCallsPerHour;
        }

        public void setMaxCallsPerHour(int maxCallsPerHour) {
            this.maxCallsPerHour = maxCallsPerHour;
        }
    }

    /**
     * Extra context for the summariser when a feed hands over a headline and
     * little else — Google News descriptions are link stubs, and a model asked
     * about nothing answers that it knows nothing. Only the page's own preview
     * text and its leading paragraphs are read, never the whole article
     * (a deliberate deviation from {@code docs/SPEC.md} §11, see DECISIONS.md).
     */
    public static class ArticleContext {

        private boolean enabled = true;
        /** Feed descriptions shorter than this are considered too thin to summarise. */
        private int minDescriptionChars = 200;
        private int maxChars = 1500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinDescriptionChars() {
            return minDescriptionChars;
        }

        public void setMinDescriptionChars(int minDescriptionChars) {
            this.minDescriptionChars = minDescriptionChars;
        }

        public int getMaxChars() {
            return maxChars;
        }

        public void setMaxChars(int maxChars) {
            this.maxChars = maxChars;
        }
    }
}
