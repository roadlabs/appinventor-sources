# offline-webapp Extension Runtime Error 问题分析报告

## 1. 结论摘要

问题根因已经定位：

> `appinventor_2026` 版本的 `replmgr.js` 在 WebRTC 扩展传输的最终 YAIL 中，仍然使用了 Kawa 不兼容的 Java 方法链式调用。

错误写法：

```scheme
(java.util.Base64:getDecoder:decode
  (apply string-append ai-chunks))
```

当前已经验证可工作的写法必须拆成两次调用：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  (apply string-append ai-chunks))
```

这个错误发生在扩展 `.jar` 文件已经完成 Base64 分片传输之后、Companion 准备解码并写入文件的最后一步。因此：

- WebRTC 与 Legacy 的连接本身正常；
- 扩展导入到 offline-webapp 本地项目也正常；
- 扩展文件枚举、路径计算、DataChannel 分片发送都可能正常；
- 但最终 YAIL 在 Companion 端被 Kawa 执行时解析失败；
- Companion 返回 `error`；
- `replmgr.js` 收到 `error` 后弹出 **Runtime Error**。

本次已修复源码文件：

```text
appinventor/blocklyeditor/src/replmgr.js
```

修复了两个位置：

1. 扩展文件写入分支；
2. 普通资源写入分支。

---

## 2. 现象分析

用户描述的现象是：

- 当前项目生成的 `offline-webapp`：
  - 带 extension 的项目在 WebRTC 模式加载正常；
  - Legacy 模式加载正常。
- 使用 `appinventor_2026` 生成的 `offline-webapp`：
  - 运行带 extension 的项目时弹出 `Runtime Error`。

这类错误通常不是 Designer 本身的 JavaScript 异常，而是 Companion 执行 YAIL 后，通过 `RetValManager` 返回了运行时错误。

在当前代码中，Runtime Error 弹窗由以下逻辑触发：

```javascript
case "error":
    console.log("processRetVals: Error value = " + r.value);
    runtimeerr(escapeHTML(r.value) + Blockly.Msg.REPL_NO_ERROR_FIVE_SECONDS);
    break;
```

也就是说，只要 Companion 返回：

```json
{
  "type": "error",
  "value": "..."
}
```

浏览器就会弹出 Runtime Error。

因此排查重点应放在：

1. Companion 收到的 YAIL；
2. 扩展文件写入流程；
3. 扩展加载之前的 `assetTransferred`；
4. Kawa 对 Java interop 表达式的解析。

---

## 3. Extension 的实际执行流程

### 3.1 Extension 导入

`.aix` 文件通过浏览器端 JSZip 解压，由：

```text
HTML5DragDrop
    ↓
LocalComponentService
    ↓
LocalProjectService
    ↓
IndexedDB
```

写入：

```text
assets/external_comps/<package>/classes.jar
assets/external_comps/<package>/components.json
```

这一部分目前是正确的。

### 3.2 连接 Companion

连接建立后：

```javascript
RefreshAssets(function() {
    Blockly.ReplMgr.loadExtensions();
});
```

WebRTC 模式下：

```javascript
rs.extensionurl = null;
```

因此会进入：

```javascript
else if (top.usewebrtc) {
    rs.state = Blockly.ReplMgr.rsState.EXTENSIONS;
    var extensionJson = JSON.stringify(top.AssetManager_getExtensions());
    extensionJson = AI.Yail.quotifyForREPL(extensionJson);
    var yailstring = "(AssetFetcher:loadExtensions " +
      extensionJson + ")";
    this.putYail.putAsset(yailstring);
}
```

Legacy 模式则通过：

```text
POST http://<companion>:8001/_extensions
```

传递扩展列表。

### 3.3 WebRTC 扩展文件传输

WebRTC 模式下，扩展文件不能使用 HTTP PUT，而是：

1. 从 IndexedDB 读取 `classes.jar`；
2. 转为 Base64；
3. 切分为多个字符串；
4. 通过 DataChannel 发送 YAIL；
5. Companion 端用 Kawa 调用 Java API；
6. 将解码后的字节写入文件；
7. 调用：

```scheme
RetValManager:assetTransferred
```

8. 最后执行：

```scheme
AssetFetcher:loadExtensions
```

当前代码中的关键分支是：

```javascript
if (!force && top.usewebrtc && top.webrtcdata) {
```

这部分整体架构是正确的。

---

## 4. 直接根因：Kawa 不支持这种方法链写法

### 4.1 当前错误代码

在 `appinventor/blocklyeditor/src/replmgr.js` 中，扩展文件的最终写入代码原来是：

```javascript
'(define ai-bytes ' +
    '(java.util.Base64:getDecoder:decode ' +
        '(apply string-append ai-chunks))) ' +
```

普通资源分支中也有同样代码：

```javascript
'(define ai-bytes ' +
    '(java.util.Base64:getDecoder:decode ' +
        '(apply string-append ai-chunks))) ' +
```

生成出来的 YAIL 逻辑等价于：

```scheme
(java.util.Base64:getDecoder:decode
  (apply string-append ai-chunks))
```

### 4.2 为什么会失败

在 Kawa 中：

```scheme
java.util.Base64:getDecoder
```

可以作为静态方法调用。

但下面这种写法：

```scheme
java.util.Base64:getDecoder:decode
```

会被 Kawa 当作连续的包名/成员路径解析，而不是：

1. 先调用 `getDecoder()`；
2. 再对返回的 Decoder 对象调用 `decode(...)`。

这会导致类似以下错误：

```text
no part 'decode'
```

或者其他 Java/Kawa interop 解析错误。

项目的故障手册已经明确记录了这一点：

```text
Kawa says `no part 'decode'`

Cause:
(java.util.Base64:getDecoder:decode chunk)

Fix:
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  chunk)
```

这与当前源码完全对应。

---

## 5. 为什么旧版本正常而 appinventor_2026 异常

通过对比当前源码和已知可工作的提交：

```text
db234a2b4 fix(webrtc): resolve assets and extension loading
```

可以确认，当前 `appinventor/blocklyeditor/src/replmgr.js` 相对于该已知可工作版本存在额外修改。

特别是当前版本新增/修改了 WebRTC 扩展写入逻辑：

```diff
+ WebRTC project transfer queue
+ saveProjectArchive
+ legacy project archive upload
```

同时，当前版本仍然保留了错误的：

```scheme
(java.util.Base64:getDecoder:decode ...)
```

而同一文件中，项目传输部分已经使用了正确写法：

```javascript
var decoderExpression = '(java.util.Base64:getDecoder)';
var bytesExpression = '(invoke ' + decoderExpression +
    ' (quote decode) "' + chunk + '")';
```

也就是说，当前代码内部已经同时存在两种写法：

### 正确写法

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  chunk)
```

出现在项目 ZIP 传输逻辑中。

### 错误写法

```scheme
(java.util.Base64:getDecoder:decode
  (apply string-append ai-chunks))
```

出现在 WebRTC 普通资源和 Extension 写入逻辑中。

这是非常明确的代码不一致问题。

---

## 6. 为什么 WebRTC 和 Legacy 都可能受影响

虽然用户观察到现有 offline-webapp 的 WebRTC 和 Legacy 都正常，而 `appinventor_2026` 生成的版本异常，但从代码分析看，需要区分两个层次。

### 6.1 Legacy Extension 加载路径

Legacy 模式的 Extension 加载主要是：

```javascript
POST rs.extensionurl
```

即：

```text
http://127.0.0.1:8001/_extensions
```

如果 Companion 自己已经可以访问扩展文件，浏览器不会走 WebRTC 的 Base64 写文件分支，因此不一定触发 `Base64:getDecoder:decode`。

### 6.2 WebRTC Extension 加载路径

WebRTC 模式一定会走：

```javascript
Blockly.ReplMgr.putAsset(...)
```

并执行：

```scheme
java.util.Base64:getDecoder:decode
```

所以该错误对 WebRTC 是确定性影响。

### 6.3 Legacy 仍然可能间接受影响的原因

Legacy 模式是否触发相同问题，取决于具体连接状态和调用路径，例如：

- 是否通过 `AssetManager` 走普通 `putAsset`；
- 是否使用了 WebRTC fallback；
- 是否连接状态被误判为 WebRTC；
- 生成后的 `offline-webapp` 是否包含了不同版本的压缩 GWT/Blockly 文件；
- 是否存在旧的 `extensionurl` 或 `hasfetchassets` 配置残留；
- 是否 Extension 文件仍需要浏览器推送给 Companion。

因此，不能仅凭“Legacy 页面也出现 Runtime Error”判断问题一定来自 Legacy HTTP 接口。最关键的是查看 Companion 返回的完整错误文本和实际发送的 YAIL。

---

## 7. 已发现的相关代码风险

### 7.1 `appinventor_2026` 生成产物可能不是当前源码的完整一致版本

当前 `offline-webapp/ode` 中存在两个 Blockly 缓存文件：

```text
offline-webapp/ode/aiblockly-7e5c8767be82558c7a3611a8ec5684c3b47b308165d61d0b68a9ad13fb2b2bd1.cache.js
offline-webapp/ode/aiblockly-ae5046ac9aa3d3e684ebb4e768eef879e3357fb51142b821247aef61a1060258.cache.js
```

这说明生成目录中可能存在不同构建时期的缓存产物。

如果 `index.html` 引用了旧的 cache 文件，而源码已经更新，可能出现：

- 源码看起来已经修复；
- 但实际运行的 `offline-webapp` 仍然执行旧代码；
- 或者不同 cache 文件之间存在函数版本差异；
- 造成“源码测试正常、打包运行异常”。

因此必须确认：

```text
offline-webapp/index.html
```

实际加载的是哪一个 `aiblockly-*.cache.js`。

### 7.2 当前仓库中缺少 skill 文档里描述的校验脚本

尝试运行：

```bash
python3 .agents/skills/appinventor-offline-webapp/scripts/check-offline-webapp.py offline-webapp
python3 .agents/skills/appinventor-offline-webapp/scripts/verify-port.py
```

但当前仓库路径下不存在这两个脚本，命令返回：

```text
No such file or directory
```

这说明当前 `.agents/skills` 目录可能只是未完整安装的技能文件，或者工作区中的 skill 路径与项目文档描述不一致。

这不会直接导致 Runtime Error，但会降低构建产物一致性验证能力。

### 7.3 仅仅修改源码不会自动修复已经生成的 offline-webapp

`offline-webapp` 是构建产物，实际运行的是：

```text
offline-webapp/ode/*.cache.js
```

而不是直接运行：

```text
appinventor/blocklyeditor/src/replmgr.js
```

因此修复源码后，必须重新生成：

```bash
./offline-tauri/build-webapp.sh
```

不能只修改 `replmgr.js` 后直接打开旧的 `offline-webapp`。

---

## 8. 本次已完成的源码修复

已经修改：

```text
appinventor/blocklyeditor/src/replmgr.js
```

将扩展写入分支从：

```javascript
'(java.util.Base64:getDecoder:decode ' +
    '(apply string-append ai-chunks))) ' +
```

修改为：

```javascript
'(invoke (java.util.Base64:getDecoder) (quote decode) ' +
    '(apply string-append ai-chunks))) ' +
```

普通资源写入分支也同步修改。

修复后的生成 YAIL 等价于：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  (apply string-append ai-chunks))
```

这样可以避免 Kawa 将 `getDecoder:decode` 当成非法的连续静态成员路径。

---

## 9. 建议的重新构建流程

在项目根目录执行：

```bash
./offline-tauri/build-webapp.sh
```

根据项目说明，该脚本会：

1. 执行 offline `ant webapp`；
2. 重新生成 GWT/Blockly 产物；
3. 复制到 `offline-webapp/`；
4. 重新生成静态 `index.html`；
5. 更新实际加载的缓存 JavaScript。

不建议单独先执行：

```bash
ant -Dlocal.services=true -Dlocale=en -Drelease=true webapp
```

因为 `build-webapp.sh` 已经封装了完整流程。

---

## 10. 建议的验证步骤

### 10.1 源码级检查

确认错误写法已经不存在：

```bash
grep -R "java.util.Base64:getDecoder:decode" \
  appinventor/blocklyeditor/src/replmgr.js
```

预期：无输出。

确认正确写法存在：

```bash
grep -n -A2 -B2 "invoke (java.util.Base64:getDecoder)" \
  appinventor/blocklyeditor/src/replmgr.js
```

预期：至少出现两处。

### 10.2 JavaScript 语法检查

```bash
node --check appinventor/blocklyeditor/src/replmgr.js
```

### 10.3 生成产物检查

重新构建后检查：

```bash
grep -R "java.util.Base64:getDecoder:decode" offline-webapp/ode
```

预期：无输出。

检查生成产物是否包含正确形式：

```bash
grep -R "invoke.*Base64:getDecoder" offline-webapp/ode
```

### 10.4 确认 index.html 使用的是最新 cache

检查：

```bash
grep -n "aiblockly-" offline-webapp/index.html
```

并确认引用的文件确实存在：

```bash
ls -l offline-webapp/ode/aiblockly-*.cache.js
```

如果存在旧 cache，应清理后重新生成，避免运行旧代码。

### 10.5 WebRTC 运行时验证

使用带 Extension 的项目，观察浏览器控制台和 Companion 返回值，重点确认顺序：

```text
1. getExtensionFileIds
2. getFileBytes
3. Base64 编码
4. DataChannel 分片发送
5. Companion 解码
6. 写入 classes.jar / <package>.jar
7. assetTransferred
8. AssetFetcher:loadExtensions
9. extensionsLoaded
```

任何情况下都应该满足：

```text
assetTransferred 发生在文件写入成功之后
```

### 10.6 重点关注的 Companion 错误

如果修复后仍然失败，应记录完整的 `r.value`，而不是只记录 Runtime Error 标题。

例如：

```javascript
case "error":
    console.log("processRetVals: Error value = " + r.value);
```

重点区分：

| Companion 返回信息 | 可能原因 |
|---|---|
| `no part 'decode'` | 旧的 Base64 方法链仍在运行 |
| `Unable to write extension jar` | 两个目标路径都写入失败 |
| `Extension ... does not exist` | 文件未成功写入或 `assetTransferred` 顺序错误 |
| `JSON Exception parsing extension string` | 扩展列表格式错误 |
| `CRC` / `extra bytes` | WebRTC 项目 ZIP 传输问题，不是普通 Extension 写入问题 |
| `ClassNotFoundException` | jar 内容损坏、路径错误或 Companion 加载路径不匹配 |

---

## 11. 需要特别区分的另一个问题：Save Project 与 Extension 加载

当前代码还包含 WebRTC 的 Save Project 功能：

```javascript
Blockly.ReplMgr.saveProjectArchive
```

这个功能与普通 Extension 加载不是同一个问题。

项目文档已经明确说明：

> WebRTC 模式下包含二进制资源的项目 ZIP 传输存在 append-only ZIP 损坏风险，可能出现 `extra bytes`、CRC 错误。

因此：

- 如果 Runtime Error 发生在“加载 Extension”阶段，应优先排查 Base64 解码和 jar 写入；
- 如果发生在“Save Project to Companion”之后打开项目，应另外排查 ZIP 传输完整性；
- 不应该把两类问题混为一谈。

当前发现的 `Base64:getDecoder:decode` 错误主要影响：

```text
WebRTC 普通资源/Extension 文件写入
```

而不是已经正确使用 `invoke` 的项目 ZIP 分块写入部分。

---

## 12. 最终判断

### 确定的问题

`appinventor_2026` 源码中的 `replmgr.js` 存在 Kawa 不兼容的 Base64 解码表达式：

```scheme
(java.util.Base64:getDecoder:decode ...)
```

这是导致 Companion 返回 Runtime Error 的首要根因。

### 已完成的修复

已将两个相关位置改为：

```scheme
(invoke
  (java.util.Base64:getDecoder)
  (quote decode)
  ...)
```

### 尚未完成的事项

由于当前环境中没有实际运行 Companion，也没有用户提供完整的 Companion `r.value` 日志，因此目前无法验证：

- 重新构建后的 `offline-webapp` 是否已完全消除 Runtime Error；
- Legacy 模式的实际错误是否与同一 YAIL 路径有关；
- 是否还存在生成产物引用旧 cache 文件的问题。

下一步必须重新运行构建脚本，并用带 Extension 的项目在 WebRTC/Legacy 两种模式下分别验证。

---

## 建议结论

优先执行：

```bash
./offline-tauri/build-webapp.sh
```

然后确认：

```bash
grep -R "java.util.Base64:getDecoder:decode" offline-webapp/ode
```

没有输出。

如果仍然弹出 Runtime Error，请提供浏览器控制台中以下日志的完整内容：

```text
processRetVals: Error value = ...
```

有了这段 Companion 返回的具体错误文本，就可以继续判断是：

1. 旧 cache 未更新；
2. Kawa 解码表达式未替换；
3. Extension 路径写入失败；
4. Android 14+ cache 路径问题；
5. Extension JSON 或 jar 本身损坏。
