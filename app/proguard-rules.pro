# Keep JavaScript interfaces out of the release build. The app deliberately
# exposes no @JavascriptInterface bridge to the WebView.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
