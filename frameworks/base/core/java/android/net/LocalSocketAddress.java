package android.net;

public class LocalSocketAddress {
    public enum Namespace {
        ABSTRACT,
        RESERVED,
        FILESYSTEM
    }

    private final String mName;
    private final Namespace mNamespace;

    public LocalSocketAddress(String name) {
        this(name, Namespace.ABSTRACT);
    }

    public LocalSocketAddress(String name, Namespace namespace) {
        mName = name;
        mNamespace = namespace;
    }

    public String getName() { return mName; }
    public Namespace getNamespace() { return mNamespace; }
}
