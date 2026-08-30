package xiaozhi.modules.appauth;

import java.util.Objects;
import java.util.function.Consumer;

/** Adapter boundary for the existing SMS provider. */
public final class SmsAppMessageSender implements AppMessageSender {
    private final Consumer<String> delivery;
    public SmsAppMessageSender(Consumer<String> delivery) { this.delivery = Objects.requireNonNull(delivery); }
    @Override public void send(String channel, String normalizedValue, String code, String purpose) {
        if ("phone".equals(channel)) delivery.accept(normalizedValue + ":" + code);
    }
}
