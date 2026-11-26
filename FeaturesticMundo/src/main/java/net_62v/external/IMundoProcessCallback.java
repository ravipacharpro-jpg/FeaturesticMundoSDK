package net_62v.external;

public interface IMundoProcessCallback {
    void onInitialized();
    void onCreate(String packageName, String processName);
}
