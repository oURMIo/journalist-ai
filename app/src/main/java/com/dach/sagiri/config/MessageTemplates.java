package com.dach.sagiri.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * User-facing texts, loaded from {@code messages.yml}. Spec section 9a forbids
 * hardcoding them in Java.
 * <p>
 * Templates use {@code {name}} placeholders substituted by {@link #render}.
 */
@Component
@ConfigurationProperties(prefix = "messages")
public class MessageTemplates {

    private String welcome = "";
    private String sourcesFallback = "";
    private String sourcesMore = "";
    private String help = "";
    private String notAdmin = "";
    private String notInTopic = "";
    private String bindUsage = "";
    private String bindNotNews = "";
    private String bindOk = "";
    private String unbindOk = "";
    private String unbindUnknown = "";
    private String newtopicUsage = "";
    private String newtopicsUsage = "";
    private String renameUsage = "";
    private String nowStarted = "";
    private String nowDone = "";
    private String topicsEmpty = "";
    private String presetsHeader = "";
    private String presetsFallbackNote = "";
    private String closetopicOk = "";
    private String filterUsage = "";
    private String filterHeader = "";
    private String filterEmpty = "";
    private String filterExcludeLabel = "";
    private String filterIncludeLabel = "";
    private String filterAiOn = "";
    private String filterAiOff = "";
    private String filterAdded = "";
    private String filterNothingAdded = "";
    private String filterLimit = "";
    private String filterRemoved = "";
    private String filterNotFound = "";
    private String filterCleared = "";
    private String filterKindExclude = "";
    private String filterKindInclude = "";
    private String topicProfileDerived = "";
    private String queryHeader = "";
    private String queryFeeds = "";
    private String queryDomainUnknown = "";
    private String queryDomainDropped = "";
    private String querySourceSlug = "";
    private String querySourcePreset = "";
    private String querySourceModel = "";
    private String querySourceUser = "";
    private String queryUsage = "";
    private String querySet = "";
    private String queryReset = "";

    /** Substitutes {@code {key}} placeholders; unknown placeholders are left untouched. */
    public static String render(String template, Map<String, ?> vars) {
        if (template == null) {
            return "";
        }
        String result = template;
        for (Map.Entry<String, ?> entry : vars.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result.strip();
    }

    public String getWelcome() {
        return welcome;
    }

    public void setWelcome(String welcome) {
        this.welcome = welcome;
    }

    public String getSourcesFallback() {
        return sourcesFallback;
    }

    public void setSourcesFallback(String sourcesFallback) {
        this.sourcesFallback = sourcesFallback;
    }

    public String getSourcesMore() {
        return sourcesMore;
    }

    public void setSourcesMore(String sourcesMore) {
        this.sourcesMore = sourcesMore;
    }

    public String getHelp() {
        return help;
    }

    public void setHelp(String help) {
        this.help = help;
    }

    public String getNotAdmin() {
        return notAdmin;
    }

    public void setNotAdmin(String notAdmin) {
        this.notAdmin = notAdmin;
    }

    public String getNotInTopic() {
        return notInTopic;
    }

    public void setNotInTopic(String notInTopic) {
        this.notInTopic = notInTopic;
    }

    public String getBindUsage() {
        return bindUsage;
    }

    public void setBindUsage(String bindUsage) {
        this.bindUsage = bindUsage;
    }

    public String getBindNotNews() {
        return bindNotNews;
    }

    public void setBindNotNews(String bindNotNews) {
        this.bindNotNews = bindNotNews;
    }

    public String getBindOk() {
        return bindOk;
    }

    public void setBindOk(String bindOk) {
        this.bindOk = bindOk;
    }

    public String getUnbindOk() {
        return unbindOk;
    }

    public void setUnbindOk(String unbindOk) {
        this.unbindOk = unbindOk;
    }

    public String getUnbindUnknown() {
        return unbindUnknown;
    }

    public void setUnbindUnknown(String unbindUnknown) {
        this.unbindUnknown = unbindUnknown;
    }

    public String getNewtopicUsage() {
        return newtopicUsage;
    }

    public void setNewtopicUsage(String newtopicUsage) {
        this.newtopicUsage = newtopicUsage;
    }

    public String getNewtopicsUsage() {
        return newtopicsUsage;
    }

    public void setNewtopicsUsage(String newtopicsUsage) {
        this.newtopicsUsage = newtopicsUsage;
    }

    public String getRenameUsage() {
        return renameUsage;
    }

    public void setRenameUsage(String renameUsage) {
        this.renameUsage = renameUsage;
    }

    public String getNowStarted() {
        return nowStarted;
    }

    public void setNowStarted(String nowStarted) {
        this.nowStarted = nowStarted;
    }

    public String getNowDone() {
        return nowDone;
    }

    public void setNowDone(String nowDone) {
        this.nowDone = nowDone;
    }

    public String getTopicsEmpty() {
        return topicsEmpty;
    }

    public void setTopicsEmpty(String topicsEmpty) {
        this.topicsEmpty = topicsEmpty;
    }

    public String getPresetsHeader() {
        return presetsHeader;
    }

    public void setPresetsHeader(String presetsHeader) {
        this.presetsHeader = presetsHeader;
    }

    public String getClosetopicOk() {
        return closetopicOk;
    }

    public void setClosetopicOk(String closetopicOk) {
        this.closetopicOk = closetopicOk;
    }

    public String getPresetsFallbackNote() {
        return presetsFallbackNote;
    }

    public void setPresetsFallbackNote(String presetsFallbackNote) {
        this.presetsFallbackNote = presetsFallbackNote;
    }

    public String getFilterUsage() {
        return filterUsage;
    }

    public void setFilterUsage(String filterUsage) {
        this.filterUsage = filterUsage;
    }

    public String getFilterHeader() {
        return filterHeader;
    }

    public void setFilterHeader(String filterHeader) {
        this.filterHeader = filterHeader;
    }

    public String getFilterEmpty() {
        return filterEmpty;
    }

    public void setFilterEmpty(String filterEmpty) {
        this.filterEmpty = filterEmpty;
    }

    public String getFilterExcludeLabel() {
        return filterExcludeLabel;
    }

    public void setFilterExcludeLabel(String filterExcludeLabel) {
        this.filterExcludeLabel = filterExcludeLabel;
    }

    public String getFilterIncludeLabel() {
        return filterIncludeLabel;
    }

    public void setFilterIncludeLabel(String filterIncludeLabel) {
        this.filterIncludeLabel = filterIncludeLabel;
    }

    public String getFilterAiOn() {
        return filterAiOn;
    }

    public void setFilterAiOn(String filterAiOn) {
        this.filterAiOn = filterAiOn;
    }

    public String getFilterAiOff() {
        return filterAiOff;
    }

    public void setFilterAiOff(String filterAiOff) {
        this.filterAiOff = filterAiOff;
    }

    public String getFilterAdded() {
        return filterAdded;
    }

    public void setFilterAdded(String filterAdded) {
        this.filterAdded = filterAdded;
    }

    public String getFilterNothingAdded() {
        return filterNothingAdded;
    }

    public void setFilterNothingAdded(String filterNothingAdded) {
        this.filterNothingAdded = filterNothingAdded;
    }

    public String getFilterLimit() {
        return filterLimit;
    }

    public void setFilterLimit(String filterLimit) {
        this.filterLimit = filterLimit;
    }

    public String getFilterRemoved() {
        return filterRemoved;
    }

    public void setFilterRemoved(String filterRemoved) {
        this.filterRemoved = filterRemoved;
    }

    public String getFilterNotFound() {
        return filterNotFound;
    }

    public void setFilterNotFound(String filterNotFound) {
        this.filterNotFound = filterNotFound;
    }

    public String getFilterCleared() {
        return filterCleared;
    }

    public void setFilterCleared(String filterCleared) {
        this.filterCleared = filterCleared;
    }

    public String getFilterKindExclude() {
        return filterKindExclude;
    }

    public void setFilterKindExclude(String filterKindExclude) {
        this.filterKindExclude = filterKindExclude;
    }

    public String getFilterKindInclude() {
        return filterKindInclude;
    }

    public void setFilterKindInclude(String filterKindInclude) {
        this.filterKindInclude = filterKindInclude;
    }

    public String getTopicProfileDerived() {
        return topicProfileDerived;
    }

    public void setTopicProfileDerived(String topicProfileDerived) {
        this.topicProfileDerived = topicProfileDerived;
    }

    public String getQueryHeader() {
        return queryHeader;
    }

    public void setQueryHeader(String queryHeader) {
        this.queryHeader = queryHeader;
    }

    public String getQueryFeeds() {
        return queryFeeds;
    }

    public void setQueryFeeds(String queryFeeds) {
        this.queryFeeds = queryFeeds;
    }

    public String getQueryDomainUnknown() {
        return queryDomainUnknown;
    }

    public void setQueryDomainUnknown(String queryDomainUnknown) {
        this.queryDomainUnknown = queryDomainUnknown;
    }

    public String getQuerySourceSlug() {
        return querySourceSlug;
    }

    public void setQuerySourceSlug(String querySourceSlug) {
        this.querySourceSlug = querySourceSlug;
    }

    public String getQuerySourcePreset() {
        return querySourcePreset;
    }

    public void setQuerySourcePreset(String querySourcePreset) {
        this.querySourcePreset = querySourcePreset;
    }

    public String getQuerySourceModel() {
        return querySourceModel;
    }

    public void setQuerySourceModel(String querySourceModel) {
        this.querySourceModel = querySourceModel;
    }

    public String getQuerySourceUser() {
        return querySourceUser;
    }

    public void setQuerySourceUser(String querySourceUser) {
        this.querySourceUser = querySourceUser;
    }

    public String getQueryUsage() {
        return queryUsage;
    }

    public void setQueryUsage(String queryUsage) {
        this.queryUsage = queryUsage;
    }

    public String getQuerySet() {
        return querySet;
    }

    public void setQuerySet(String querySet) {
        this.querySet = querySet;
    }

    public String getQueryReset() {
        return queryReset;
    }

    public void setQueryReset(String queryReset) {
        this.queryReset = queryReset;
    }

    public String getQueryDomainDropped() {
        return queryDomainDropped;
    }

    public void setQueryDomainDropped(String queryDomainDropped) {
        this.queryDomainDropped = queryDomainDropped;
    }
}
