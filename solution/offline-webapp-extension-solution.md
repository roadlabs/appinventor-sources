# offline-webapp Extension Runtime Error 解决方案

## 1. 文档目的

本文档说明 `appinventor_2026` 生成的 offline-webapp 在运行带 Extension 项目时出现 `Runtime Error` 的解决方案，并详细列出相关代码文件、修改内容、构建流程和验证方法。

本文针对以下场景：

- Extension 已经能够通过 `.aix` 导入 offline-webapp；
- Extension 在 IndexedDB 中能够看到；
- WebRTC 或 Legacy 连接能够建立；
- Companion 在加载 Extension 时弹出 `Runtime Error`；
- 当前已知可工作的 offline-webapp 版本没有该问题，而 `appinventor_2026` 版本出现问题。

---

## 2. 最终解决结论

问题出在：

```text
appinventor/blocklyeditor/src/replmgr.js
```

WebRTC 文件传输的最终 YAIL 使用了 Kawa 不支持的 Java 方法链式调用：

```scheme
(java.util.Base64:getDecoder:decode
  (apply string-append ai-chunks))
```

必须改为使用 `invoke`，明确分开静态方法调用和实例方法调用：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  (apply string-append ai-chunks))
```

该修改需要在 `replmgr.js` 中出现的两个位置同时完成：

1. Extension 文件写入分支；
2. 普通资源文件写入分支。

只修改其中一个位置会留下同类问题。

---

## 3. 根因说明

### 3.1 Runtime Error 的触发位置

`replmgr.js` 收到 Companion 返回的 `error` 消息后，会调用 Runtime Error 对话框：

```javascript
case "error":
    console.log("processRetVals: Error value = " + r.value);
    runtimeerr(escapeHTML(r.value) + Blockly.Msg.REPL_NO_ERROR_FIVE_SECONDS);
    break;
```

因此，浏览器弹出的 `Runtime Error` 不是根因，而是 Companion 执行 YAIL 失败后的表现。

### 3.2 错误的 YAIL 调用方式

原代码生成：

```scheme
(java.util.Base64:getDecoder:decode
  (apply string-append ai-chunks))
```

这看起来像是：

```text
Base64.getDecoder().decode(...)
```

但 Kawa 不会按照 JavaScript 或 Java 的方式解释这种连续冒号表达式。它可能将其解析为一个连续的静态成员路径，最终导致类似以下错误：

```text
no part 'decode'
```

或者其他 Kawa Java interop 解析错误。

### 3.3 正确的 Kawa 写法

应当先调用静态方法：

```scheme
(java.util.Base64:getDecoder)
```

得到 Decoder 对象，再通过 `invoke` 调用实例方法：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  data)
```

这也是项目故障手册中规定的兼容写法。

---

## 4. 相关代码文件

### 4.1 必须修改的文件

```text
appinventor/blocklyeditor/src/replmgr.js
```

该文件负责：

- 与 AI Companion 建立 Legacy/WebRTC 连接；
- 管理 YAIL 队列；
- 传输普通资源；
- 传输 Extension 文件；
- 处理 Companion 返回的 `assetTransferred`、`extensionsLoaded` 和 `error` 消息。

### 4.2 需要检查但通常不需要修改的文件

```text
appinventor/appengine/src/com/google/appinventor/client/AssetManager.java
appinventor/appengine/src/com/google/appinventor/client/local/LocalProjectService.java
appinventor/appengine/src/com/google/appinventor/client/YaClient.gwt.xml
appinventor/components/src/com/google/appinventor/components/runtime/util/AssetFetcher.java
```

这些文件决定：

- Extension 文件是否从 IndexedDB 读取；
- Extension 文件路径是否正确；
- 本地服务是否被 GWT deferred binding 选中；
- Companion 是否调用 `loadExtensions`。

当前问题的首要根因在 `replmgr.js`，不应为了修复该问题修改 AI Companion runtime 代码。

---

## 5. `replmgr.js` 的详细修改说明

## 5.1 修改位置一：Extension 文件写入分支

在 `Blockly.ReplMgr.putAsset` 中，Extension 文件最终写入代码位于：

```javascript
if (isExt) {
    finalYail = '(begin ' +
        '(define ai-bytes ' +
            '(java.util.Base64:getDecoder:decode ' +
                '(apply string-append ai-chunks))) ' +
        ...
}
```

### 修改前

```javascript
'(define ai-bytes ' +
    '(java.util.Base64:getDecoder:decode ' +
        '(apply string-append ai-chunks))) ' +
```

### 修改后

```javascript
'(define ai-bytes ' +
    '(invoke (java.util.Base64:getDecoder) (quote decode) ' +
        '(apply string-append ai-chunks))) ' +
```

### 修改后的完整关键片段

```javascript
if (isExt) {
    finalYail = '(begin ' +
        '(define ai-bytes ' +
            '(invoke (java.util.Base64:getDecoder) (quote decode) ' +
                '(apply string-append ai-chunks))) ' +
        '(define ai-written #f) ' +
        '(try-catch ' +
            '(begin (java.nio.file.Files:write ai-target1-path ai-bytes) ' +
                '(set! ai-written #t)) ' +
            '(ex java.lang.Throwable #f)) ' +
        '(try-catch ' +
            '(begin (java.nio.file.Files:write ai-target2-path ai-bytes) ' +
                '(set! ai-written #t)) ' +
            '(ex java.lang.Throwable #f)) ' +
        '(if ai-written ' +
            '(begin (set! ai-chunks (quote ())) ' +
                '(com.google.appinventor.components.runtime.util.RetValManager:assetTransferred "assets/' + shortFn + '")) ' +
            '(error "Unable to write extension jar")))';
}
```

### 修改目的

该分支负责写入 Extension JAR：

- Android 旧版本路径：

```text
replAssetDir/external_comps/<package>/classes.jar
```

- Android 14 及以上路径：

```text
cacheDir/external_comps/<package>/<package>.jar
```

在写文件前必须先将 Base64 字符串解码成字节数组。原来的 Kawa 表达式在解码阶段就失败，因此两个目标文件都不会被正确写入。

---

## 5.2 修改位置二：普通资源写入分支

同一个 `putAsset` 函数中，普通资源的最终写入分支也使用了同样的错误写法。

### 修改前

```javascript
'(define ai-bytes ' +
    '(java.util.Base64:getDecoder:decode ' +
        '(apply string-append ai-chunks))) ' +
```

### 修改后

```javascript
'(define ai-bytes ' +
    '(invoke (java.util.Base64:getDecoder) (quote decode) ' +
        '(apply string-append ai-chunks))) ' +
```

### 修改后的关键片段

```javascript
} else {
    finalYail = '(begin ' +
        '(define ai-bytes ' +
            '(invoke (java.util.Base64:getDecoder) (quote decode) ' +
                '(apply string-append ai-chunks))) ' +
        '(try-catch ' +
            '(java.nio.file.Files:write ai-target-path ai-bytes) ' +
            '(ex java.lang.Throwable #t)) ' +
        '(set! ai-chunks (quote ())) ' +
        '(com.google.appinventor.components.runtime.util.RetValManager:assetTransferred "assets/' + shortFn + '"))';
}
```

### 修改目的

该分支负责普通资源文件的写入。虽然当前问题主要表现为 Extension 加载失败，但普通资源也使用相同的解码表达式。如果只修改 Extension 分支，后续传输图片、声音或其他资源时仍可能再次出现相同 Runtime Error。

---

## 6. 不应修改的代码

### 6.1 不要修改 AI Companion runtime

不要通过修改以下目录下的文件来规避问题：

```text
appinventor/components/src/com/google/appinventor/components/runtime/
```

特别是不要修改：

```text
ReplForm.java
AssetFetcher.java
RetValManager.java
```

原因：

- offline-webapp 的设计目标是兼容现有、未修改的 AI Companion；
- 当前问题可以在浏览器端生成正确的 YAIL 来解决；
- 修改 Companion 会破坏兼容性并偏离当前项目的架构边界。

### 6.2 不要重新启用 `fetchAssets`

offline-webapp 没有 App Engine 后端，不能依赖：

```text
/ode/download/file/...
/ode/download/project-cached/...
```

WebRTC Extension 文件应通过 DataChannel 和 YAIL 直接写入 Companion，不应重新使用 Companion 通过 HTTPS 从 offline-webapp 下载资源的旧路径。

### 6.3 不要恢复 WebRTC 下的 HTTP PUT

模式规则如下：

| 模式 | Extension/资源传输方式 |
|---|---|
| Legacy | Companion `:8001` HTTP 接口 |
| WebRTC | DataChannel 文本 YAIL + Kawa/Java 文件写入 |

WebRTC 下不能因为 Base64 解码失败就退回 HTTP PUT。

---

## 7. 相关文件的检查说明

## 7.1 `AssetManager.java`

需要确认以下逻辑没有被删除：

```java
$wnd.Blockly.ReplMgr.putAsset(projectId, filename, content, function() {
  window.parent.AssetManager_markAssetTransferred(filename)
});
```

还需要确认 Extension 文件不会因为不在普通资源列表中而阻塞计数。当前实现对未登记的 Extension 文件返回成功，符合设计：

```java
if (assetInfo == null) {
  return true;
}
```

此文件通常无需修改。

## 7.2 `LocalProjectService.java`

需要确认以下桥接函数存在：

```text
LocalProjectService_getFileBytes
LocalProjectService_getExtensionFileIds
```

并且同时注册到：

```javascript
$wnd
$wnd.top
```

原因是：

- GWT Java 代码可能运行在 bootstrap iframe；
- `replmgr.js` 可能运行在外层页面；
- 只注册 `$wnd` 会导致外层页面看不到 Extension 文件桥接函数。

当前正确做法是同时导出：

```java
$wnd.LocalProjectService_getFileBytes = getFileBytes;
$wnd.LocalProjectService_getExtensionFileIds = getExtensionFileIds;

if ($wnd.top && $wnd.top !== $wnd) {
  $wnd.top.LocalProjectService_getFileBytes = getFileBytes;
  $wnd.top.LocalProjectService_getExtensionFileIds = getExtensionFileIds;
}
```

此文件通常无需因本次 Base64 问题而修改。

## 7.3 `YaClient.gwt.xml`

需要确认 offline 服务绑定仍然正确：

```xml
<replace-with class="com.google.appinventor.client.local.LocalProjectService">
  <when-type-is class="com.google.appinventor.shared.rpc.project.ProjectService"/>
  <when-property-is name="local.services" value="true"/>
</replace-with>
```

TokenAuth 必须使用正确的接口路径：

```xml
<when-type-is class="com.google.appinventor.shared.rpc.tokenauth.TokenAuthService"/>
```

注意是：

```text
shared.rpc.tokenauth.TokenAuthService
```

而不是：

```text
shared.rpc.user.TokenAuthService
```

此文件通常无需因本次 Kawa 解码问题而修改。

## 7.4 `AssetFetcher.java`

Companion 端的：

```java
AssetFetcher.loadExtensions(String jsonString)
```

负责最终调用：

```java
form.loadComponents(extensionsToLoad);
```

浏览器端的目标是确保在调用 `loadExtensions` 之前，Extension JAR 已经成功写入正确路径。

不要修改 `AssetFetcher.java` 来绕过文件写入失败。

---

## 8. WebRTC Extension 传输的正确顺序

修复后，WebRTC Extension 加载必须遵循以下顺序：

```text
1. 连接 WebRTC DataChannel
2. 刷新本地资源列表
3. 获取 Extension 文件列表
4. 从 IndexedDB 读取 classes.jar
5. 转换为 Base64
6. 分片生成 YAIL
7. 发送初始化 YAIL
8. 发送 Base64 分片
9. 发送最终解码/写文件 YAIL
10. Companion 成功写入文件
11. Companion 返回 assetTransferred
12. 发送 AssetFetcher:loadExtensions
13. Companion 加载 Extension
14. Companion 返回 extensionsLoaded
15. 浏览器将连接状态设为 CONNECTED
```

尤其要保证：

```text
assetTransferred 必须发生在文件写入成功之后
```

不能在浏览器刚刚将数据放入发送队列时就调用成功回调。

---

## 9. 修改后的完整验证方法

### 9.1 检查源码中是否仍有错误写法

```bash
grep -R "java.util.Base64:getDecoder:decode" \
  appinventor/blocklyeditor/src/replmgr.js
```

预期结果：

```text
无输出
```

如果仍有输出，说明修改不完整。

### 9.2 检查正确写法

```bash
grep -n -A2 -B2 "invoke (java.util.Base64:getDecoder)" \
  appinventor/blocklyeditor/src/replmgr.js
```

预期至少看到两处：

1. Extension 分支；
2. 普通资源分支。

### 9.3 JavaScript 语法检查

```bash
node --check appinventor/blocklyeditor/src/replmgr.js
```

该命令没有输出且返回成功，表示 JavaScript 语法通过。

### 9.4 重新生成 offline-webapp

必须重新生成构建产物：

```bash
./offline-tauri/build-webapp.sh
```

不要只修改源码后直接运行旧的 `offline-webapp`。

### 9.5 检查生成产物

```bash
grep -R "java.util.Base64:getDecoder:decode" offline-webapp/ode
```

预期：无输出。

然后检查生成产物中是否存在正确表达式：

```bash
grep -R "invoke.*Base64:getDecoder" offline-webapp/ode
```

如果源码已经修复，但生成产物仍包含旧字符串，说明构建没有使用最新源码或 `offline-webapp` 中残留旧缓存。

### 9.6 检查 `index.html` 使用的 Blockly cache

```bash
grep -n "aiblockly-" offline-webapp/index.html
```

确认 `index.html` 引用的文件实际存在：

```bash
ls -l offline-webapp/ode/aiblockly-*.cache.js
```

如果目录中有多个旧 cache 文件，应以 `index.html` 实际引用的文件为准，并确认该文件包含修复后的逻辑。

---

## 10. WebRTC 回归测试

使用一个包含 Extension 的测试项目，至少验证以下内容：

### 10.1 Extension 导入

- 拖入 `.aix` 文件；
- Extension 出现在 Designer 的扩展列表；
- 项目刷新后 Extension 仍然存在；
- IndexedDB 中存在：

```text
assets/external_comps/<package>/classes.jar
assets/external_comps/<package>/components.json
```

### 10.2 WebRTC 连接

- 建立 WebRTC 连接；
- 控制台没有 `LocalProjectService_* is undefined`；
- 控制台没有 `/ode/download/file` 请求；
- 控制台没有 `TokenAuthService_Proxy` 请求。

### 10.3 文件传输

确认 Companion 依次收到：

```text
初始化 YAIL
Base64 分片 YAIL
最终 decode/write YAIL
```

并观察是否收到：

```text
assetTransferred
```

### 10.4 Extension 加载

确认最终收到：

```text
extensionsLoaded
```

且不再出现：

```text
Runtime Error
```

---

## 11. Legacy 回归测试

Legacy 模式与 WebRTC 模式必须分别验证。

Legacy 模式应满足：

```text
1. 不使用 DataChannel
2. 使用 Companion :8001 HTTP 接口
3. Extension 文件使用 Companion 支持的 Legacy 路径
4. 不请求 App Engine 下载地址
5. Extension 能够正常加载
```

Legacy 项目保存时，项目文件名必须相对于 Companion 的 `assets` 目录：

正确：

```text
__projects__/ProjectName
```

错误：

```text
assets/__projects__/ProjectName
```

本次 Base64 修复主要针对 WebRTC YAIL 文件写入，但普通资源分支同步修改后，可以避免 Legacy 相关边缘路径再次使用错误表达式。

---

## 12. 错误日志定位表

如果重新构建后仍然失败，请记录浏览器控制台中的完整信息：

```text
processRetVals: Error value = ...
```

根据具体错误判断：

| 错误信息 | 可能原因 | 下一步 |
|---|---|---|
| `no part 'decode'` | 仍在运行旧的 Base64 方法链 | 检查源码和生成 cache |
| `Unable to write extension jar` | 两个 Extension 目标路径都写入失败 | 检查路径、权限和 Companion Android 版本 |
| `Extension ... does not exist` | 文件未成功写入或加载顺序错误 | 检查 `assetTransferred` 和 `loadExtensions` 顺序 |
| `JSON Exception parsing extension string` | Extension 列表 JSON 错误 | 检查 `AssetManager_getExtensions()` |
| `ClassNotFoundException` | JAR 损坏、路径错误或包名不匹配 | 校验 JAR 大小、哈希和目标路径 |
| `CRC` / `extra bytes` | WebRTC 项目 ZIP 传输损坏 | 与普通 Extension 传输问题分开处理 |
| `/ode/download/file` | Offline 服务回退到了在线路径 | 检查 `AssetManager`、`LocalProjectService` 和 GWT binding |
| `TokenAuthService_Proxy` | Deferred binding 未生效 | 检查 `YaClient.gwt.xml` |

---

## 13. 常见错误修复方式

### 13.1 只修改一个 Base64 调用位置

错误做法：

- 只修改 Extension 分支；
- 忘记普通资源分支。

后果：

- Extension 可能正常；
- 普通图片、声音或其他资源仍然触发 Runtime Error。

正确做法：

```text
全文件搜索 java.util.Base64:getDecoder:decode
并全部替换为 invoke 形式。
```

### 13.2 只修改源码、不重新构建

错误做法：

```text
修改 replmgr.js 后直接打开旧 offline-webapp
```

后果：

- 旧的 GWT cache 仍然被加载；
- 实际运行代码没有变化；
- 问题看起来像“修复无效”。

正确做法：

```bash
./offline-tauri/build-webapp.sh
```

### 13.3 用 JavaScript 的方法链思维编写 Kawa

错误：

```scheme
(java.util.Base64:getDecoder:decode data)
```

正确：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  data)
```

### 13.4 通过修改 Companion 来规避

不建议：

- 修改 `ReplForm.java`；
- 修改 `AssetFetcher.java`；
- 修改 Companion 的文件目录规则。

正确方向：

```text
在浏览器端生成 Companion 已经支持的正确 YAIL。
```

---

## 14. 推荐补丁

建议最终源码保持以下形式：

```javascript
var decoderExpression = '(java.util.Base64:getDecoder)';

var bytesExpression = '(invoke ' + decoderExpression +
    ' (quote decode) "' + chunk + '")';
```

对于最终合并后的字符串：

```javascript
'(define ai-bytes ' +
    '(invoke (java.util.Base64:getDecoder) (quote decode) ' +
        '(apply string-append ai-chunks))) ' +
```

全项目应避免出现：

```javascript
java.util.Base64:getDecoder:decode
```

---

## 15. 影响范围

### 直接影响

- WebRTC 模式下 Extension JAR 写入；
- WebRTC 模式下普通资源写入；
- Companion 执行最终 YAIL；
- `assetTransferred` 回调；
- `AssetFetcher:loadExtensions` 的后续执行。

### 间接影响

- Extension 加载状态可能一直停留在 `EXTENSIONS`；
- Companion 可能提示 Extension 不存在；
- 浏览器可能弹出 Runtime Error；
- 后续项目初始化代码无法继续执行。

### 不直接影响

- `.aix` 的浏览器端导入；
- IndexedDB 项目存储；
- Legacy HTTP PUT 本身；
- Designer 的 Extension metadata 解析；
- Companion runtime 的 Java 代码。

---

## 16. 最终验收标准

满足以下条件即可认为解决方案完成：

1. `replmgr.js` 中不存在 `java.util.Base64:getDecoder:decode`；
2. Extension 和普通资源分支均使用 `invoke` 解码；
3. `node --check appinventor/blocklyeditor/src/replmgr.js` 通过；
4. 重新生成 `offline-webapp`；
5. 生成产物中不存在旧的错误字符串；
6. WebRTC 带 Extension 项目加载成功；
7. Legacy 带 Extension 项目加载成功；
8. Companion 返回 `assetTransferred`；
9. Companion 返回 `extensionsLoaded`；
10. 浏览器不再弹出 Runtime Error；
11. 不需要修改 AI Companion；
12. 浏览器不请求不存在的 App Engine 下载接口。

---

## 17. 总结

本问题不是 Extension ZIP 导入问题，也不是 IndexedDB 存储问题，而是 WebRTC 文件传输最后阶段生成的 Kawa YAIL 不兼容。

核心修复只有一个原则：

> Kawa 中不要使用 `java.util.Base64:getDecoder:decode` 这种方法链式写法，必须先调用 `getDecoder`，再使用 `invoke` 调用 `decode`。

最终正确形式为：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  data)
```

完成源码修改后，必须重新执行 offline-webapp 构建流程，确保修复进入实际加载的 GWT/Blockly cache 文件。