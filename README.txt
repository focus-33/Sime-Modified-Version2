Sime 编译错误修复补丁

修复内容：
在 Android/app/src/main/java/com/shiyu/sime/ime/keyboard/T9KeyboardView.java
增加缺失的导入：import android.widget.TextView;

原因：makeLeftItem() 的返回类型使用 TextView，但源文件没有导入该类，导致 javac 报 cannot find symbol。

应用方法：将本补丁中的 Android 目录内容覆盖到项目对应位置。
本补丁适用于 Sime-Modified-Version-letter-popup.zip（上一轮交付版本）。
