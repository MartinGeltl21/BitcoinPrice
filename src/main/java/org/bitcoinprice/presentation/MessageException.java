package org.bitcoinprice.presentation;

/** Validation error translated for the command recipient instead of the machine locale. */
public final class MessageException extends IllegalArgumentException {
    private final String key;
    private final Object[] arguments;

    public MessageException(String key, Object... arguments) {
        super(Language.GERMAN.text(key, arguments));
        this.key = key;
        this.arguments = arguments.clone();
    }

    public MessageException(String key, Throwable cause) {
        this(key);
        initCause(cause);
    }

    public String text(Language language) { return language.text(key, arguments); }
}
