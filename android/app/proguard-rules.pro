# リリース版の R8 の設定（build.gradle.kts の release）。
# 使っていないライブラリの部品を除くだけにして、危ないことはしない：

# 名前を書き換えない（落ちたときの記録・再生の記録に、そのままの名前が残るように）
-dontobfuscate

# このアプリのコードは全部残す（画面・サービス・設定の部品は名前で作られることがあるので）
-keep class com.ryose.videoplayer.** { *; }

# libVLC は native（C）の側から Java の部品を名前で呼ぶので、全部残す
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**
