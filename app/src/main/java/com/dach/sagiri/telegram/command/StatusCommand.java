package com.dach.sagiri.telegram.command;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.schedule.PollingState;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;

/** Active topics, last poll time and the outbound queue depth. */
@Component
public class StatusCommand implements BotCommand {

    private final TelegramSender sender;
    private final TopicRegistryService registry;
    private final PollingState state;
    private final BotProperties properties;

    public StatusCommand(TelegramSender sender, TopicRegistryService registry,
        PollingState state, BotProperties properties) {
        this.sender = sender;
        this.registry = registry;
        this.state = state;
        this.properties = properties;
    }

    @Override
    public String command() {
        return "/status";
    }

    @Override
    public void execute(CommandContext context) {
        List<TopicEntity> topics = registry.pollableTopics().stream()
            .filter(topic -> topic.getChatId() == context.chatId())
            .toList();

        StringBuilder out = new StringBuilder("<b>Status</b>\n")
            .append("Active topics: ").append(topics.size()).append('\n')
            .append("Poll interval: ").append(properties.getPollIntervalMinutes())
            .append(" min\n")
            .append("Last successful poll: ")
            .append(state.lastSuccessfulPoll().map(Instant::toString).orElse("none yet"))
            .append('\n')
            .append("Runs: ").append(state.runs())
            .append(", news sent: ").append(state.messagesSent()).append('\n')
            .append("Send queue: ").append(sender.queueSize()).append('\n');

        for (TopicEntity topic : topics) {
            out.append("• <code>").append(HtmlText.escape(topic.getSlug())).append("</code> — ")
                .append(topic.getLastPolledAt() == null ? "not polled yet"
                    : topic.getLastPolledAt().toString())
                .append('\n');
        }
        sender.reply(context.chatId(), context.threadId(), out.toString().strip());
    }
}
