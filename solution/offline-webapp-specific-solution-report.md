# offline-webapp Specific Solution Report

**Project:** `appinventor_2026`  
**Branch:** `offline-webapp/2026-09-03-from-master`  
**Solution date:** September 3, 2026  
**Scope:** Make repository/template import, splash-screen tutorial loading, and Export menu operations work in the generated offline webapp.

## 1. Recommended implementation strategy

Use the existing browser-local architecture rather than adding a new dependency or restoring App Engine calls:

```text
GWT UI
  ↓
LocalProjectService / LocalDownloader / LocalUserInfoService
  ↓
IndexedDB + packaged static assets
  ↓
JSZip + browser/Tauri file-save API
```

The project already uses JSZip, IndexedDB, GWT `Promise`, and the Tauri bridge in the local implementations. Reuse those mechanisms.

Do not modify AI Companion runtime code. Do not re-enable these online-only endpoints for offline workflows:

```text
/ode/download/project-source/...
/ode/download/selected-projects-source/...
/ode/download/all-projects-source
/ode/download/userfile/...
/ode/projects
/ode/upload/...
```

## 2. Files to change

### Required source changes

```text
appinventor/appengine/src/com/google/appinventor/client/wizards/TemplateUploadWizard.java
appinventor/appengine/src/com/google/appinventor/client/local/LocalProjectService.java
appinventor/appengine/src/com/google/appinventor/client/local/LocalDownloader.java
appinventor/appengine/src/com/google/appinventor/client/local/LocalUserInfoService.java
appinventor/appengine/src/com/google/appinventor/client/local/LocalUploader.java
appinventor/appengine/src/com/google/appinventor/client/explorer/dialogs/NoProjectDialogBox.java
```

### Build/package changes

```text
appinventor/appengine/build.xml or the offline webapp packaging script
offline-webapp/templates/                 # generated output, not hand-maintained
offline-webapp/index.html                 # generated output, if the packaging script needs a manifest hook
```

Prefer adding the template-copy step to the existing offline build script rather than manually editing generated cache files.

## 3. Fix 1 — repository import and template dialog

### 3.1 Package the built-in repository

Copy the built-in template data needed by the UI from:

```text
appinventor/appengine/war/templates/
```

to the generated artifact:

```text
offline-webapp/templates/
```

At minimum include:

```text
templates/templates.json
templates/HelloPurr/HelloPurr.zip
templates/HelloPurr/HelloPurr.json
templates/HelloPurr/screenshot.png
templates/HelloPurr/thumbnail.png
templates/SimpleChatbot/SimpleChatbot.zip
templates/SimpleChatbot/SimpleChatbot.json
templates/SimpleChatbot/screenshot.png
templates/SimpleChatbot/thumbnail.png
```

For full parity, include every existing built-in template directory and its archive/media files. The source tree currently has examples such as `DIYBookClub`, `MapTheMovement`, `MoodRing`, `MyToDoList`, `SnapchatRemix`, `SoundLibrary`, and `TranslateApp`.

If `templates/templates.json` does not exist in the source directory, generate it during packaging from the individual `*.json` descriptors. It must be a JSON array of template descriptors, for example:

```json
[
  {
    "name": "HelloPurr",
    "subtitle": "...",
    "description": "...",
    "screenshot": "screenshot.png",
    "thumbnail": "thumbnail.png"
  }
]
```

Do not use an empty array for the built-in repository.

### 3.2 Make the local service return the packaged manifest

Replace the current stub:

```java
@Override
public void retrieveTemplateData(String pathToTemplates, AsyncCallback<String> callback) {
  callback.onSuccess("[]");
}
```

with a browser-local read of the packaged `templates/templates.json`. The implementation should:

1. Resolve the URL relative to the current offline artifact/module base;
2. Use an asynchronous XHR/`RequestBuilder` GET;
3. Return the response text when HTTP status is 200;
4. Return a useful error through `callback.onFailure` for a missing/corrupt manifest;
5. Work when the application is opened from the Tauri shell and when served from a local static origin.

The preferred URL should be derived from the actual page/module location, not hard-coded to an App Engine host. For a static artifact opened at the webapp root, the expected URL is:

```text
templates/templates.json
```

If direct `file://` access is a supported mode, verify the browser's file-origin behavior. Where the browser blocks XHR from `file://`, provide the manifest as an embedded static resource or have the packaging script inject the manifest into a generated JavaScript object. Do not make the entire wizard depend on an unavailable network server.

### 3.3 Add an empty-list guard in the wizard

Even with a valid packaged manifest, the wizard must not crash on an empty or malformed repository. Change `TemplateUploadWizard` so that:

- `createUI()` handles `templates == null` or `templates.isEmpty()`;
- `makeTemplateSelector()` does not call `list.get(0)` unless the list is nonempty;
- `populateTemplateDialog()` displays an informational empty-repository state instead of indexing element zero;
- the Finish button is disabled when no template is selected;
- `selectedTemplateNAME` is reset when the repository changes.

This converts malformed data from a JavaScript exception into a visible, recoverable UI state.

### 3.4 Import a packaged built-in archive

The current built-in branch computes:

```java
String pathToZip = "templates/" + projectName + "/" + projectName + ".zip";
ode.getProjectService().newProjectFromTemplate(
    projectNameInExplorer, pathToZip, callback);
```

Implement `LocalProjectService.newProjectFromTemplate()` as follows:

1. Resolve the packaged archive URL, normally `templates/<name>/<name>.zip`;
2. Fetch it as an `ArrayBuffer` using XHR/fetch;
3. Convert it to the format already accepted by `newProjectFromExternalTemplate()` (Base64 or a direct JSZip binary load, depending on the existing JSZip wrapper contract);
4. Delegate to the existing `newProjectFromExternalTemplate(projectName, zipData, callback)` path;
5. Preserve the requested explorer name when importing;
6. Reject with a descriptive error if the archive is missing or invalid.

The existing `newProjectFromExternalTemplate()` should remain the single archive-import implementation so that local file uploads and packaged templates have identical behavior.

Important: `newProjectFromTemplate()` must never call `callback.onSuccess(null)` for a valid built-in template. `null` is interpreted by `TemplateUploadWizard` as “the template has no archive” and causes a blank project to be created.

### 3.5 Support external repositories explicitly

For a user-entered external repository, retain the current `RequestBuilder` path but improve it by:

- validating the repository URL;
- checking HTTP status before parsing JSON;
- handling malformed JSON without uncaught exceptions;
- displaying a CORS/network-specific message;
- keeping the packaged built-in repository available if the external repository fails.

External repositories necessarily remain network-dependent. The offline guarantee applies to packaged built-in templates, not arbitrary remote URLs.

## 4. Fix 2 — Help → Show Splash → Go to Tutorial

### 4.1 Route built-in tutorials through local packaged archives

The first two tutorial buttons already use the desired abstraction:

```java
createProjectFromExistingZip("HelloPurr", ...)
createProjectFromExistingZip("SimpleChatbot", ...)
```

After implementing `newProjectFromTemplate()`, these actions will load the packaged archives.

Retain the existing project-name collision handling. The import callback must add the imported `UserProject` to `ProjectManager`, load its root tree, and open it in Designer.

### 4.2 Replace the online Translate tutorial dependency

The third button currently hard-codes:

```text
https://appinventor.mit.edu/yrtoolkit/yr/aiaFiles/hello_bonjour/translate_tutorial.asc
```

Add a packaged offline archive for this tutorial under the local templates directory, preferably:

```text
templates/TranslateApp/TranslateApp.zip
```

Then change `NoProjectDialogBox.handleGoToYR()` to call the same built-in archive path used by the other tutorials. If the source archive is not available under that name, package the existing project archive under a stable local name and use that name consistently in the Java code and manifest.

Do not leave this button dependent on a remote `.asc` URL if the requirement is offline operation.

### 4.3 Prevent duplicate or stale wizard state

`TemplateUploadWizard` stores repository data in static fields. Ensure that:

- `initializeBuiltInTemplates()` replaces, rather than appends to, prior built-in data;
- `setStoredTemplateUrls()` clears or deduplicates `dynamicTemplateUrls` before loading settings;
- a newly created tutorial wizard sets `usingExternalTemplate = false` and `templateHostUrl = ""`;
- `selectedTemplateNAME` is initialized from the chosen tutorial name before import.

This avoids state left over from a previously opened external repository affecting a splash tutorial.

## 5. Fix 3 — Export menu

### 5.1 Centralize local archive creation

Keep `LocalProjectService.exportProject(long)` as the primitive for one project, but make it robust:

- await `ensureLoadedAsync()`;
- include every non-directory project file, including binary assets and extensions;
- preserve the original project-relative paths;
- reject if the project does not exist or has no files;
- attach an error callback in `LocalDownloader` so failures are reported instead of appearing as a no-op.

The existing JSZip dependency and Base64 archive output should be reused.

### 5.2 Add selected-project export

Extend `LocalDownloader.download(String path)` with a branch for:

```text
download/selected-projects-source/<id>-<id>-...
```

Implementation:

1. Parse the trailing hyphen-separated project IDs, ignoring the final empty segment;
2. Await `ensureLoadedAsync()`;
3. Create a new outer JSZip archive;
4. For each selected project, add its exported files under a stable directory such as `<ProjectName>/`;
5. Generate a Base64 ZIP;
6. Save it as a descriptive filename such as `selected-projects.aia` or `AppInventorProjects.aia` using the same `triggerDownload()` bridge;
7. Report invalid IDs and ZIP errors through `ErrorReporter`.

If the online implementation uses a single archive containing multiple project directories, mirror that layout. The archive must remain importable or at least preserve each complete project without path collisions.

A cleaner implementation is to add a `LocalProjectService.exportProjects(List<Long>)` method that builds the outer archive directly and have `LocalDownloader` call it.

### 5.3 Add export-all

Extend `LocalDownloader.download(String path)` with a branch for:

```text
download/all-projects-source
```

Implementation:

1. Obtain all non-trash project IDs from the local project map;
2. Build one outer JSZip archive containing every project under its project name;
3. Generate Base64;
4. Trigger a browser/Tauri save as `all-projects.aia`;
5. Handle the empty-project case with an informational message.

The confirmation dialog in `ExportAllProjectsAction` can remain unchanged.

### 5.4 Add a shared download helper

`LocalDownloader.triggerDownload()` currently handles Base64 archive output and already has a Tauri branch plus a browser-anchor fallback. Reuse it for all project archives.

Improve it to:

- catch malformed Base64;
- report Tauri dialog or filesystem errors to `ErrorReporter` or the console;
- avoid silently ignoring a rejected save promise;
- use the correct archive MIME type (`application/zip` or `application/octet-stream`);
- preserve the `.aia` extension.

### 5.5 Implement keystore state and export

The current local user service has empty methods for user files. Implement the minimal local keystore workflow:

- Store `android.keystore` bytes in IndexedDB, either in the existing contents store under a reserved user-file key or in a new `userFiles` object store;
- implement `LocalUserInfoService.hasUserFile()` with a callback that returns `true` only when the file exists;
- implement `LocalUserInfoService.deleteUserFile()` and its callback;
- implement `LocalUploader`'s `UPLOAD_USERFILE` handler to read the selected file into bytes and persist it;
- add a local downloader `userfile` branch that reads the bytes and invokes `triggerFileDownload()`;
- keep the filename `android.keystore`.

The “Export keystore” menu action then follows its existing flow:

```text
hasUserFile → local downloader userfile → save dialog/browser download
```

If keystore upload is deliberately out of scope for this release, at minimum implement `hasUserFile()` as `false` with a callback and disable the menu item consistently. Do not leave an async callback permanently pending.

### 5.6 Do not confuse project export with file download

`LocalDownloader` already has a separate `file` branch for media downloads. Keep these paths distinct:

| URL kind | Output |
|---|---|
| `project-source` | One `.aia` archive |
| `selected-projects-source` | One archive containing selected projects |
| `all-projects-source` | One archive containing all projects |
| `file` | One original project file |
| `userfile` | One user file, such as `android.keystore` |

## 6. Build and generated-artifact requirements

The source fixes will not affect the existing `offline-webapp` until the artifact is regenerated.

Use the repository’s offline build script and do not hand-edit GWT cache files. The build must:

1. Compile the GWT module with `local.services=true`;
2. Generate the GWT permutation and Blockly assets;
3. Copy packaged templates into `offline-webapp/templates/`;
4. Generate/update the static `offline-webapp/index.html`;
5. Ensure `index.html` references the newly generated cache files;
6. Remove stale generated caches or ensure the active entrypoint cannot select them;
7. Preserve Tauri packaging permissions for the dialog and filesystem APIs.

The output must be tested using the exact `index.html` that users open.

## 7. Validation plan

### 7.1 Static source checks

```bash
# Confirm the local template stub is gone
grep -R -n 'callback.onSuccess("\[\]")' \
  appinventor/appengine/src/com/google/appinventor/client/local/LocalProjectService.java

# Confirm the null template stub is gone
grep -R -n 'newProjectFromTemplate' \
  appinventor/appengine/src/com/google/appinventor/client/local/LocalProjectService.java

# Confirm all local downloader branches exist
grep -R -n -E 'PROJECT_SOURCE|SELECTED_PROJECTS_SOURCE|ALL_PROJECTS_SOURCE|DOWNLOAD_USERFILE' \
  appinventor/appengine/src/com/google/appinventor/client/local/LocalDownloader.java

# Confirm async user-file callbacks are implemented
grep -R -n -A5 'hasUserFile' \
  appinventor/appengine/src/com/google/appinventor/client/local/LocalUserInfoService.java
```

### 7.2 Archive/package checks

```bash
find offline-webapp/templates -type f | sort
unzip -t offline-webapp/templates/HelloPurr/HelloPurr.zip
unzip -t offline-webapp/templates/SimpleChatbot/SimpleChatbot.zip
```

Verify that every packaged tutorial archive contains:

```text
youngandroidproject/project.properties
src/.../*.scm
src/.../*.bky
```

and that binary assets remain binary.

### 7.3 Build checks

Run the project’s prescribed offline build script from the repository root. Then verify:

```bash
grep -n 'aiblockly-' offline-webapp/index.html
ls -l offline-webapp/ode/aiblockly-*.cache.js
```

The file named by `index.html` must exist and contain the current local-service strings.

Run the project typecheck/build and relevant tests available in this repository. At minimum perform the Java/GWT build and any local-service or webapp verification scripts supplied by the project.

### 7.4 Manual functional tests

#### Repository import

1. Open the newly generated `offline-webapp/index.html`.
2. Select Projects → Import project from a repository.
3. Confirm the “Create a Project from a Template” dialog opens.
4. Confirm built-in templates are listed with thumbnails/descriptions.
5. Select HelloPurr or another template.
6. Click Finish.
7. Confirm the project appears in the project list and opens in Designer.
8. Reload the page and confirm the imported project remains in IndexedDB.

#### Splash tutorials

1. Select Help → Show Splash.
2. Click each Go to Tutorial button.
3. Confirm each action creates/imports the expected project.
4. Confirm no network request to `appinventor.mit.edu` or an App Engine download servlet is needed.
5. Confirm each project can be re-opened after page reload.

#### Export

1. Create or import one project and export it.
2. Select multiple projects and use Export selected projects.
3. Use Export all projects.
4. Open each downloaded archive with `unzip -t`.
5. Confirm project paths and binary assets are intact.
6. Upload/import the single-project `.aia` into another App Inventor instance or the local webapp and verify its contents.
7. If keystore support is included, upload a test keystore, reload, and export it; compare bytes with a checksum.

#### Direct file-origin/Tauri checks

Test the actual supported launch modes separately:

- browser opening of the static artifact;
- local static HTTP serving if used by the product;
- Tauri shell.

The core IndexedDB and packaged-template workflows must not silently depend on an App Engine server.

## 8. Error handling requirements

Every asynchronous local operation must terminate with either success or failure:

- no empty callback bodies;
- no `callback.onSuccess(null)` for successful archive imports;
- no unhandled rejected promises;
- no silent `LocalDownloader` fall-through for recognized export paths;
- no uncaught `list.get(0)` on empty template data.

Error messages should identify the operation and the missing local resource, for example:

```text
Unable to load packaged template manifest: templates/templates.json
Unable to load packaged template archive: templates/HelloPurr/HelloPurr.zip
Unable to export selected projects: project <id> was not found
Unable to export keystore: android.keystore is not present
```

## 9. Implementation order

Implement in this order to minimize rework:

1. Add/copy the built-in template manifest and archives to the generated artifact.
2. Implement `retrieveTemplateData()` against that local package.
3. Add empty-list protection in `TemplateUploadWizard`.
4. Implement `newProjectFromTemplate()` by delegating to the existing JSZip import path.
5. Change the Translate tutorial to a packaged local archive.
6. Add selected-project and export-all branches to `LocalDownloader`.
7. Add error handling to single-project export.
8. Implement user-file/keystore callbacks and download/upload if keystore export is required.
9. Regenerate the entire offline artifact.
10. Run static checks, build checks, archive checks, and the manual regression matrix.

## 10. Acceptance criteria

The work is complete when all of the following are true:

1. Import project from a repository opens the template dialog without a JavaScript exception.
2. The packaged built-in manifest is nonempty and its archives are present in `offline-webapp`.
3. Selecting a built-in template imports a real `UserProject`, not a blank-project fallback.
4. HelloPurr, SimpleChatbot, and Translate App load without remote tutorial URLs.
5. A single project exports as a valid `.aia` file.
6. Multiple selected projects export as one valid archive.
7. Export all projects exports every local project as one valid archive.
8. Keystore export either works end to end or is explicitly and consistently disabled with a completed callback; it must not hang.
9. No export URL falls through silently in `LocalDownloader`.
10. The generated `offline-webapp` is rebuilt from the fixed sources.
11. The active `index.html` points to the current generated cache.
12. No App Engine backend or AI Companion modification is required for these workflows.
13. Existing WebRTC/Legacy Companion behavior remains covered by its separate regression tests.
