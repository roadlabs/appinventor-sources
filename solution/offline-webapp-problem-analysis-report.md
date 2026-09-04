# offline-webapp Problem Analysis Report

**Project:** `appinventor_2026`  
**Branch:** `offline-webapp/2026-09-03-from-master`  
**Analysis date:** September 3, 2026  
**Scope:** Repository/template import, Help → Show Splash → Go to Tutorial, and Export menu behavior.

## 1. Executive summary

The three reported failures have a common cause: the generated offline web application selects local service implementations through GWT deferred binding, but several UI paths still depend on online server behavior or on incomplete local-service stubs.

The relevant binding is present in `appinventor/appengine/src/com/google/appinventor/YaClient.gwt.xml`:

- `ProjectService` → `LocalProjectService`
- `Downloader` → `LocalDownloader`
- `Uploader` → `LocalUploader`
- `UserInfoService` → `LocalUserInfoService`

This means the offline artifact does not use the normal App Engine RPC and servlet implementations. Every UI action that reaches one of those services must therefore have a complete local implementation.

### Confirmed root causes

| Feature | Confirmed root cause | Result |
|---|---|---|
| Import project from a repository | `LocalProjectService.retrieveTemplateData()` returns `[]`; `TemplateUploadWizard` assumes the list contains at least one template and calls `list.get(0)` | The wizard throws during construction, so the “Create a Project from a Template” dialog cannot be displayed |
| Tutorial example projects | `LocalProjectService.newProjectFromTemplate()` immediately calls `callback.onSuccess(null)`; the two bundled tutorial buttons use this method, while the third tutorial uses an online `.asc` URL | The examples are not imported; the first two fall back to blank-project creation logic and the third requires an unavailable remote URL |
| Export menu | `LocalDownloader` handles only `project-source` and `file`; it does not handle `selected-projects-source`, `all-projects-source`, or `userfile`; `LocalUserInfoService.hasUserFile()` never invokes its callback | Single-project and designer export paths are partial at best; export-all, multi-select export, and keystore export do not complete |

## 2. Repository/template import failure

### 2.1 UI path

The Projects toolbar binds the repository action as follows:

```text
TopToolbar.ui.xml
  ImportTemplate → ImportTemplateAction

ImportTemplateAction.execute()
  new TemplateUploadWizard().center()
```

`TemplateUploadWizard` constructs its initial UI with the built-in template list:

```java
this.addPage(createUI(builtInTemplates));
populateTemplateDialog(builtInTemplates);
```

The template selector then creates a cell using the first element:

```java
TemplateCell templateCell = new TemplateCell(list.get(0), templateHostUrl);
```

There is no empty-list guard in the current implementation.

### 2.2 Local service response

The offline implementation currently contains:

```java
@Override
public void retrieveTemplateData(String pathToTemplates, AsyncCallback<String> callback) {
  callback.onSuccess("[]");
}
```

At startup, `Ode.retrieveTemplateData()` calls this service and passes the result to:

```java
TemplateUploadWizard.initializeBuiltInTemplates(json);
```

Consequently:

```text
builtInTemplates = []
templatesMap[Built-in Templates] = []
```

When the user clicks Import project from a repository, `TemplateUploadWizard` calls `list.get(0)` on that empty list. The exception occurs before the dialog can be rendered. This explains why the dialog itself does not appear rather than merely showing an empty repository.

### 2.3 Additional offline limitation

The upstream template implementation assumes that built-in archives are available from the App Engine WAR path:

```text
templates/<Project>/<Project>.zip
```

The generated `offline-webapp` currently contains no equivalent `templates/` directory or generated template manifest. Therefore, even if the empty-list crash were avoided, the offline artifact has no packaged source from which to populate or import built-in templates.

Remote repositories are a separate case. `TemplateUploadWizard.retrieveExternalTemplateData()` uses an HTTP GET for:

```text
<host>/templates/templates.json
```

and external project import uses another HTTP GET for the encoded archive. Those operations require network access and CORS. They cannot be guaranteed in a genuinely offline application and should not be the only implementation for the built-in repository.

## 3. Tutorial example loading failure

The Help → Show Splash screen includes the three “Go to Tutorial” actions in:

```text
appinventor/appengine/src/com/google/appinventor/client/explorer/dialogs/NoProjectDialogBox.java
```

### 3.1 HelloPurr and SimpleChatbot

The first two actions call:

```java
new TemplateUploadWizard().createProjectFromExistingZip(
    "HelloPurr", new NewTutorialProject(), "HelloPurr");
```

and:

```java
new TemplateUploadWizard().createProjectFromExistingZip(
    "SimpleChatbot", new NewTutorialProject(), "SimpleChatbot");
```

For built-in templates, `createProjectFromExistingZip()` eventually calls:

```java
ode.getProjectService().newProjectFromTemplate(
    projectNameInExplorer, pathToZip, callback);
```

The current local implementation is only:

```java
@Override
public void newProjectFromTemplate(String projectName, String pathToZip,
    AsyncCallback<UserProject> callback) {
  callback.onSuccess(null);
}
```

The callback in `TemplateUploadWizard` interprets `null` as “this template has no aia file” and starts a new blank project. Thus the archive is never read and the tutorial project cannot be loaded.

The source repository does contain the required archives:

```text
appinventor/appengine/war/templates/HelloPurr/HelloPurr.zip
appinventor/appengine/war/templates/SimpleChatbot/SimpleChatbot.zip
```

They are valid ZIP archives with App Inventor project files, but they are not copied into `offline-webapp`.

### 3.2 Translate App tutorial

The third action calls:

```java
TemplateUploadWizard.openProjectFromTemplate(
    Window.Location.getProtocol()
        + "//appinventor.mit.edu/yrtoolkit/yr/aiaFiles/hello_bonjour/translate_tutorial.asc",
    new NewTutorialProject());
```

This is an online URL. `openTemplateProject()` uses `RequestBuilder.GET`, and the response is then passed to `newProjectFromExternalTemplate()`.

In an offline artifact, this path fails whenever the network is unavailable, the remote server is unavailable, or the response is blocked by browser/CORS/file-origin restrictions. It also violates the expectation that the built-in splash tutorials work without an App Engine backend.

### 3.3 Project import implementation is otherwise partially present

`LocalProjectService.newProjectFromExternalTemplate()` already contains a browser-side JSZip/IndexedDB import path. It:

1. Loads a Base64 project archive with JSZip;
2. Reads each non-directory entry as an `ArrayBuffer`;
3. Writes the bytes to IndexedDB;
4. Builds a `UserProject` and project tree;
5. Calls the project callback.

This implementation can be reused for both local file upload and packaged built-in archives. The missing piece is connecting `newProjectFromTemplate()` to a locally packaged archive rather than returning `null`.

## 4. Export menu failure

### 4.1 Single-project export

The standard action in `ExportProjectAction` constructs:

```text
download/project-source/<projectId>
```

for one selected project or for the currently open designer project.

`LocalDownloader` recognizes `project-source` and calls:

```java
projectService.exportProject(projectId)
```

The existing `LocalProjectService.exportProject(long)` uses JSZip and returns a Base64 archive. `LocalDownloader` then triggers either a Tauri save dialog or a browser anchor download.

This is a valid partial implementation, but it depends on the generated offline artifact containing the current source code. It also has no explicit error handler for a rejected export promise, so a failed ZIP generation can appear to the user as “nothing happened.”

### 4.2 Multi-select export

When more than one project is selected, `ExportProjectAction` constructs:

```text
download/selected-projects-source/<id>-<id>-...
```

`LocalDownloader.download()` has no branch for `selected-projects-source`. It silently returns after parsing the path, so no file is generated and no download is started.

### 4.3 Export all projects

`ExportAllProjectsAction` constructs:

```text
download/all-projects-source
```

after the confirmation dialog. `LocalDownloader` has no branch for `all-projects-source`, so the action has no offline effect.

### 4.4 Keystore export

The Export/File menu also contains “Export keystore.” Its action first calls:

```java
UserInfoService.hasUserFile("android.keystore", callback)
```

The local implementation is empty:

```java
@Override
public void hasUserFile(String fileName, AsyncCallback<Boolean> callback) {
}
```

The callback is never invoked. The menu action therefore cannot determine whether a keystore exists and cannot reach the download step.

Even if the callback were implemented, `LocalDownloader` has no `userfile` branch, and `LocalUploader` has no `userfile` handler for the corresponding local keystore upload path. The keystore workflow is therefore incomplete in both directions.

## 5. Generated artifact observations

The working tree contains a generated `offline-webapp` directory with:

```text
offline-webapp/index.html
offline-webapp/ode/*.cache.js
offline-webapp/static/*
```

`index.html` loads the generated GWT and Blockly caches, not the Java source files directly. Therefore, source-level fixes do not affect the currently opened artifact until the webapp is rebuilt.

The active artifact includes strings from the current local-service implementation, including:

```text
IndexedDB unavailable
Empty .aix archive
Unable to load Project Template Data
templates.json
project-source
```

This confirms that the generated artifact contains the local service code, but it does not prove that the three requested workflows are complete. In particular, the artifact still has no packaged built-in template directory.

## 6. Impact and boundaries

### Directly affected

- Import project from a repository / built-in template wizard;
- Built-in splash-screen tutorial projects;
- External `.asc` tutorial loading while offline;
- Export selected project(s);
- Export all projects;
- Keystore existence detection and export.

### Not the primary cause

- IndexedDB itself: it is already used by the local project service and supports binary `ArrayBuffer` content;
- JSZip availability: it is already used by the local project import/export code;
- AI Companion runtime: no Companion modification is required for these three browser-side workflows;
- The previously analyzed WebRTC Base64/Kawa issue: that is a separate Companion-transfer issue and should not be conflated with the current template/export failures.

## 7. Final diagnosis

The failures are not three unrelated UI defects. They are incomplete local-service substitutions:

1. Template metadata is stubbed as an empty list while the wizard assumes nonempty data.
2. Built-in template import is stubbed as a successful `null` callback instead of reading the archive.
3. The splash tutorial code still points at online template paths.
4. The local downloader implements only one of the three project-source URL forms.
5. User-file/keystore callbacks and downloads are not implemented.
6. The generated static artifact does not package the built-in template archives.

The correct fix is to complete the browser-local data path and then regenerate `offline-webapp`; modifying server endpoints or the AI Companion would not solve the root problem.