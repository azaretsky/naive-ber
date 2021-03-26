package name.funny.ber;

public class BERDecodingException extends Exception {
    public BERDecodingException(String message) {
        super(message);
    }

    public BERDecodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
