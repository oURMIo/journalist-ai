package com.dach.sagiri.summary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;
import com.dach.sagiri.news.model.NewsItem;

/**
 * The default summariser: no keys, no cost, no network beyond the feed itself.
 * <p>
 * Strips the feed HTML with Jsoup — leaving it in would break
 * {@code parse_mode=HTML} — and keeps the first few sentences.
 */
public class ExtractiveSummarizer implements Summarizer {

    static final int MAX_SENTENCES = 3;
    static final int MAX_LENGTH = 500;

    /** Sentence end followed by whitespace; abbreviations are not worth the complexity here. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    @Override
    public String summarize(NewsItem item) {
        String text = plainText(item.rawDescription());
        if (StringUtils.isBlank(text)) {
            // No description in the feed: the headline is all we honestly have.
            return StringUtils.trimToEmpty(item.title());
        }

        List<String> sentences = new ArrayList<>();
        int length = 0;
        for (String sentence : SENTENCE_END.split(text)) {
            String candidate = sentence.strip();
            if (candidate.isEmpty()) {
                continue;
            }
            if (!sentences.isEmpty() && length + candidate.length() + 1 > MAX_LENGTH) {
                break;
            }
            sentences.add(candidate);
            length += candidate.length() + 1;
            if (sentences.size() == MAX_SENTENCES) {
                break;
            }
        }

        String summary = String.join(" ", sentences);
        return summary.length() <= MAX_LENGTH
            ? summary
            : summary.substring(0, MAX_LENGTH - 1).stripTrailing() + "…";
    }

    /** Feed descriptions are HTML fragments; everything markup-ish has to go. */
    public static String plainText(String html) {
        if (StringUtils.isBlank(html)) {
            return "";
        }
        String cleaned = Jsoup.clean(html, "", Safelist.none(),
            new Document.OutputSettings().prettyPrint(false));
        return Parser.unescapeEntities(cleaned, false).replaceAll("\\s+", " ").strip();
    }
}
