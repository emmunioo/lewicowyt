# Aplikacja używa wyłącznie publicznych API Androida i nie wymaga własnych reguł.

# kmp-tor → kmp-process: klasa PID próbuje java.lang.management.* (ścieżka JVM), a na Androidzie
# schodzi na fallback. Tych klas nie ma w Androidzie, więc R8 tylko je ignoruje (nie są używane w runtime).
-dontwarn java.lang.management.**

# A-03: jxl_jni.c woła z C metodę `onPass(Bitmap)` PO NAZWIE (GetMethodID) — R8 nie może jej zmienić nazwy
# ani usunąć, inaczej dekodowanie progresywne kończy się NoSuchMethodError. Natywne metody NativeJxl też.
-keep interface pl.lewicowyt.notifier.images.NativeJxl$PassListener { void onPass(android.graphics.Bitmap); }
-keepclasseswithmembernames class pl.lewicowyt.notifier.images.NativeJxl { native <methods>; }

# F-22: w RELEASE usuń wszystkie wywołania android.util.Log (bezpośrednie Log.w/i/e w kodzie
# społeczności/Tora/JNI potrafiły trafić do logcat z nazwą hosta .onion lub komunikatem wyjątku).
# Metody nie mają efektów ubocznych, więc R8 usuwa też budowanie ich argumentów (string templates).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
