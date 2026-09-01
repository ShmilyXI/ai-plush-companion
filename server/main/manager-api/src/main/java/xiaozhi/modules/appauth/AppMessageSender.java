package xiaozhi.modules.appauth;

public interface AppMessageSender {
    void send(String channel, String normalizedValue, String code, String purpose);

    static AppMessageSender noop() {
        return (channel, value, code, purpose) -> {
            // Delivery is supplied by the configured production adapter.
        };
    }
}
