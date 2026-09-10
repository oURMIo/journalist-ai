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
}
