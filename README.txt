Sime 九宫格字母浮动选择窗修复补丁

适用基线：Sime-Modified-Version-scroll-reset.zip（上一轮交付的完整源码）

覆盖文件（保持目录结构）：
1. Android/app/src/main/java/com/shiyu/sime/ime/keyboard/T9KeyboardView.java
2. Android/app/src/main/java/com/shiyu/sime/ime/keyboard/framework/KeyView.java

修改内容：
- 长按 2-9 键 300ms 后，在当前按键上方显示字母浮动窗。
- 默认高亮第二个字母。
- 手指在当前按键的水平边界内移动即可切换字母，不再按整个键盘宽度计算。
- 浮动窗不接收触摸，手指仍由原按键持续跟踪；抬起提交高亮字母，取消不提交。
- 原按键数字/字母标签不再被临时替换。
- 1 键仍按此前约定：短按原快捷符号/分词，长按大小写锁定。

将这两个文件覆盖到项目对应路径即可。不要叠加更早的九宫格字母手势补丁。
