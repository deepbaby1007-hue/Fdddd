# 앱은 WebView 자바스크립트만 사용하므로 별도 난독화 규칙이 필요하지 않습니다.
# 릴리스 빌드에서 minify를 켤 경우를 대비한 기본 규칙만 둡니다.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
