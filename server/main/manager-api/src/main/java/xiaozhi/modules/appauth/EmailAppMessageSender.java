package xiaozhi.modules.appauth;

import java.util.Objects;
import java.util.function.Consumer;

/** Adapter boundary for the configured mail provider. */
public final class EmailAppMessageSender implements AppMessageSender {
    private final Consumer<String> delivery;
    public EmailAppMessageSender(Consumer<String> delivery) { this.delivery = Objects.requireNonNull(delivery); }
    @Override public void send(String channel, String normalizedValue, String code, String purpose) {
        if ("email".equals(channel)) delivery.accept(normalizedValue + ":" + code);
    }
}
