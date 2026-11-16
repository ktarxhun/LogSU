# Keep libsu classes
-keep class com.topjohnwu.superuser.** { *; }

# app_process ile kabuk uzerinden cagrilan root yardimcilari: kodda statik referans
# olmadigi icin R8 acildiginda silinmemeleri ve main imzalarini korumalari gerekir.
-keep class com.ktarxhun.logsu.root.RootAudioRecorder { *; }
-keep class com.ktarxhun.logsu.root.RootDisplayRecorder { *; }
-keep class com.ktarxhun.logsu.root.RootAudioRecorderKt { *; }
-keepclassmembers class com.ktarxhun.logsu.root.RootAudioRecorder {
    public static void main(java.lang.String[]);
}
-keepclassmembers class com.ktarxhun.logsu.root.RootDisplayRecorder {
    public static void main(java.lang.String[]);
}
