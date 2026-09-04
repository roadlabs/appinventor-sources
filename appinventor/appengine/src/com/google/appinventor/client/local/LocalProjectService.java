// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2024-2025 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.local;

import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_ACCENT_COLOR;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_ACTIONBAR;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_APP_NAME;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_BLOCK_SUBSET;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_DEFAULTFILESCOPE;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_ICON;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_PRIMARY_COLOR;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_PRIMARY_COLOR_DARK;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_PROJECT_COLORS;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_SHOW_LISTS_AS_JSON;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_SIZING;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_THEME;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_TUTORIAL_URL;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_USES_LOCATION;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_VERSION_CODE;
import static com.google.appinventor.shared.settings.SettingsConstants.YOUNG_ANDROID_SETTINGS_VERSION_NAME;

import com.google.appinventor.client.ErrorReporter;
import com.google.appinventor.client.Ode;
import com.google.appinventor.client.jzip.GenerateOptions;
import com.google.appinventor.client.jzip.JSZip;
import com.google.appinventor.client.jzip.LoadOptions;
import com.google.appinventor.client.jzip.TextDecoder;
import com.google.appinventor.client.jzip.TextEncoder;
import com.google.appinventor.client.jzip.Type;
import com.google.appinventor.client.settings.project.ProjectSettings;
import com.google.appinventor.client.settings.project.YoungAndroidSettings;
import com.google.appinventor.client.utils.Promise;
import com.google.appinventor.components.common.YaVersion;
import com.google.appinventor.shared.properties.json.JSONUtil;
import com.google.appinventor.shared.rpc.RpcResult;
import com.google.appinventor.shared.rpc.project.ChecksumedLoadFile;
import com.google.appinventor.shared.rpc.project.FileDescriptor;
import com.google.appinventor.shared.rpc.project.FileDescriptorWithContent;
import com.google.appinventor.shared.rpc.project.NewProjectParameters;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.appinventor.shared.rpc.project.ProjectRootNode;
import com.google.appinventor.shared.rpc.project.ProjectServiceAsync;
import com.google.appinventor.shared.rpc.project.TextFile;
import com.google.appinventor.shared.rpc.project.UserProject;
import com.google.appinventor.shared.rpc.project.youngandroid.NewYoungAndroidProjectParameters;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetsFolder;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidBlocksNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidComponentNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidComponentsFolder;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidFormNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidPackageNode;
import com.google.gwt.core.client.JsArrayString;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidProjectNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidSourceFolderNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidSourceNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidYailNode;
import com.google.appinventor.shared.settings.SettingsConstants;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONNumber;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONString;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.typedarrays.shared.ArrayBuffer;
import com.google.gwt.user.client.rpc.AsyncCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-browser implementation of {@link ProjectServiceAsync} backed by IndexedDB.
 *
 * <p>All persistent state - both project metadata and file contents - lives in
 * the {@code ai2-offline} IndexedDB database (see {@link LocalIdbStore}). The
 * legacy {@code ai2-offline-state} localStorage key, if present, is read once
 * on first load, written into IDB, and then removed.
 *
 * <p>RPCs that read or write project / file state first await
 * {@link #ensureLoadedAsync()} so the in-memory caches are fully populated
 * before the user's callback fires.
 */
public class LocalProjectService implements ProjectServiceAsync {
  /** Max size of a media file (in bytes) for which a hover preview will be generated. */
  static final int MAX_PREVIEW_SIZE = 2 * 1024 * 1024;

  private final Map<String, UserProject> projects = new HashMap<>();
  private final Map<Long, ProjectRootNode> projectData = new HashMap<>();
  private final Map<String, ArrayBuffer> contents = new HashMap<>();
  private final Map<String, String> dataUrlCache = new HashMap<>();

  private Promise<Void> loadPromise;
  private Promise<Void> persistChain = Promise.resolve(null);
  private boolean idbUnavailable;

  /** Minimal embedded fallback used when a browser blocks file-origin XHR. */
  private static final String EMBEDDED_TEMPLATE_MANIFEST =
      "[{\"name\":\"DIYBookClub\",\"subtitle\":\"Wire and share book reviews with friends.\","
          + "\"description\":\"\",\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"DontGetFaked\",\"subtitle\":\"Quiz app to detect if a story is fake or not.\","
          + "\"description\":\"\",\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"HelloPurr\",\"subtitle\":\"A purring kitty app\",\"description\":\"\","
          + "\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"HelloPurrStarter\",\"subtitle\":\"A purring kitty app\",\"description\":\"\","
          + "\"screenshot\":\"../HelloPurr/screenshot.png\",\"thumbnail\":\"../HelloPurr/thumbnail.png\"},"
          + "{\"name\":\"MapTheMovement\",\"subtitle\":\"Search for Twitter hashtags by location using a map.\","
          + "\"description\":\"\",\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"MoodRing\",\"subtitle\":\"Track your feelings and reach out to others having a rough time.\","
          + "\"description\":\"\",\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"MyToDoList\",\"subtitle\":\"Keep track of your daily tasks.\",\"description\":\"\","
          + "\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"SimpleChatbot\",\"subtitle\":\"Create your very own ChatGPT app.\",\"description\":\"\","
          + "\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"SnapchatRemix\",\"subtitle\":\"Take a photo, then draw on it in different colors.\","
          + "\"description\":\"\",\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"SoundLibrary\",\"subtitle\":\"Capture, store, and play found sounds.\",\"description\":\"\","
          + "\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"},"
          + "{\"name\":\"TranslateApp\",\"subtitle\":\"Quickly translate English to Spanish.\",\"description\":\"\","
          + "\"screenshot\":\"screenshot.png\",\"thumbnail\":\"thumbnail.png\"}]";

  String getProjectName(String projectId) {
    // Caller path doesn't go through RPC, so we don't await. If a caller fires
    // before load completes they'll just see null; the only such caller is
    // LocalDownloader.triggerDownload which is initiated by user action after
    // the project list is rendered, well after load has resolved.
    long hash = Long.parseLong(projectId);
    for (Map.Entry<String, UserProject> entry : projects.entrySet()) {
      if (entry.getValue().getProjectId() == hash) {
        return entry.getKey();
      }
    }
    return null;
  }

  // -------- Loading & persistence --------

  /**
   * Resolves once the in-memory {@code projects} / {@code projectData} / {@code contents} caches
   * are populated from IndexedDB (and any legacy localStorage migration has been attempted).
   */
  public Promise<Void> ensureLoadedAsync() {
    if (loadPromise == null) {
      loadPromise = doLoad();
    }
    return loadPromise;
  }

  private Promise<Void> doLoad() {
    final String[] projectsJson = new String[1];
    final JavaScriptObject[] contentsMap = new JavaScriptObject[1];
    return LocalIdbStore.open()
        .then0(() -> LocalIdbStore.getMeta(LocalIdbStore.PROJECTS_KEY))
        .then(pj -> {
          projectsJson[0] = pj;
          return LocalIdbStore.getAllContents();
        })
        .then(cm -> {
          contentsMap[0] = cm;
          return Promise.resolve(null);
        })
        .then0(() -> {
          Promise<Void> next = Promise.resolve(null);
          if (projectsJson[0] == null) {
            // No meta record yet. First run on this origin: try to migrate
            // any legacy localStorage data, otherwise we are starting fresh.
            String legacy = LocalIdbStore.loadLegacyLocalStorage();
            if (legacy != null && !legacy.isEmpty()) {
              next = migrateLegacy(legacy);
            }
          } else {
            parseProjectsJson(projectsJson[0], projects);
          }
          loadContentsIntoCache(contentsMap[0]);
          rebuildProjectData();
          return next;
        })
        .error(err -> {
          idbUnavailable = true;
          ErrorReporter.reportInfo(
              "IndexedDB unavailable; projects and files will not persist across reloads.");
          return Promise.resolve(null);
        });
  }

  private Promise<Void> migrateLegacy(String legacyJson) {
    Map<String, ArrayBuffer> legacyContents = new HashMap<>();
    List<UserProject> legacyProjects = new ArrayList<>();
    if (!parseLegacyJson(legacyJson, legacyProjects, legacyContents)) {
      return Promise.resolve(null);
    }
    // Seed in-memory caches immediately so the current session works even if
    // the migration writes below fail partway.
    for (UserProject p : legacyProjects) {
      projects.put(p.getProjectName(), p);
    }
    contents.putAll(legacyContents);

    // Persist contents and project list to IDB. Only remove the legacy
    // localStorage key after every write succeeds; otherwise a transient IDB
    // failure would leave us without a copy.
    List<Promise<Void>> writes = new ArrayList<>();
    for (Map.Entry<String, ArrayBuffer> e : legacyContents.entrySet()) {
      writes.add(LocalIdbStore.putContent(e.getKey(), e.getValue()));
    }
    String projectsJson = buildProjectsJson();
    writes.add(LocalIdbStore.putMeta(LocalIdbStore.PROJECTS_KEY, projectsJson));
    return Promise.allOf(writes.toArray(new Promise[0]))
        .then(v -> {
          LocalIdbStore.clearLegacyLocalStorage();
          return Promise.resolve(null);
        })
        .error(err -> {
          // Leave the legacy localStorage key in place; next load will retry.
          return Promise.resolve(null);
        });
  }

  private static boolean parseLegacyJson(String json,
      List<UserProject> outProjects, Map<String, ArrayBuffer> outContents) {
    try {
      JSONValue parsed = JSONParser.parseLenient(json);
      if (parsed == null) {
        return false;
      }
      JSONObject root = parsed.isObject();
      if (root == null) {
        return false;
      }
      JSONArray projectsArr = root.get("projects").isArray();
      if (projectsArr != null) {
        for (int i = 0; i < projectsArr.size(); i++) {
          JSONObject p = projectsArr.get(i).isObject();
          if (p == null) {
            continue;
          }
          JSONValue idV = p.get("id");
          JSONValue nameV = p.get("name");
          JSONValue typeV = p.get("type");
          JSONValue createdV = p.get("created");
          JSONValue modifiedV = p.get("modified");
          if (idV == null || nameV == null || typeV == null
              || createdV == null || modifiedV == null) {
            continue;
          }
          JSONNumber idN = idV.isNumber();
          JSONString nameS = nameV.isString();
          JSONString typeS = typeV.isString();
          JSONNumber createdN = createdV.isNumber();
          JSONNumber modifiedN = modifiedV.isNumber();
          if (idN == null || nameS == null || typeS == null
              || createdN == null || modifiedN == null) {
            continue;
          }
          outProjects.add(new UserProject(
              (long) idN.getValue(), nameS.stringValue(), typeS.stringValue(),
              (long) createdN.getValue(), (long) modifiedN.getValue()));
        }
      }
      JSONArray contentsArr = root.get("contents").isArray();
      if (contentsArr != null) {
        for (int i = 0; i < contentsArr.size(); i++) {
          JSONArray pair = contentsArr.get(i).isArray();
          if (pair == null || pair.size() != 2) continue;
          JSONString keyS = pair.get(0).isString();
          JSONString b64S = pair.get(1).isString();
          if (keyS == null || b64S == null) continue;
          outContents.put(keyS.stringValue(), base64ToBuffer(b64S.stringValue()));
        }
      }
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private static void parseProjectsJson(String json, Map<String, UserProject> out) {
    try {
      JSONValue parsed = JSONParser.parseLenient(json);
      if (parsed == null) {
        return;
      }
      JSONArray arr = parsed.isArray();
      if (arr == null) {
        return;
      }
      for (int i = 0; i < arr.size(); i++) {
        JSONObject p = arr.get(i).isObject();
        if (p == null) {
          continue;
        }
        JSONValue idV = p.get("id");
        JSONValue nameV = p.get("name");
        JSONValue typeV = p.get("type");
        JSONValue createdV = p.get("created");
        JSONValue modifiedV = p.get("modified");
        if (idV == null || nameV == null || typeV == null
            || createdV == null || modifiedV == null) {
          continue;
        }
        JSONNumber idN = idV.isNumber();
        JSONString nameS = nameV.isString();
        JSONString typeS = typeV.isString();
        JSONNumber createdN = createdV.isNumber();
        JSONNumber modifiedN = modifiedV.isNumber();
        if (idN == null || nameS == null || typeS == null
            || createdN == null || modifiedN == null) {
          continue;
        }
        out.put(nameS.stringValue(), new UserProject(
            (long) idN.getValue(), nameS.stringValue(), typeS.stringValue(),
            (long) createdN.getValue(), (long) modifiedN.getValue()));
      }
    } catch (Exception ex) {
      // ignore - corrupt meta file shouldn't kill the session
    }
  }

  private void loadContentsIntoCache(JavaScriptObject map) {
    if (map == null) {
      return;
    }
    JsArrayString keys = LocalIdbStore.keysToArray(map);
    for (int i = 0; i < keys.length(); i++) {
      String key = keys.get(i);
      ArrayBuffer buf = LocalIdbStore.mapGet(map, key);
      if (buf != null) {
        contents.put(key, buf);
      }
    }
  }

  private void rebuildProjectData() {
    projectData.clear();
    for (Map.Entry<String, UserProject> e : projects.entrySet()) {
      long id = e.getValue().getProjectId();
      projectData.put(id, reconstructTree(id, e.getKey()));
    }
  }

  private ProjectRootNode reconstructTree(long projectId, String projectName) {
    YoungAndroidProjectNode root = new YoungAndroidProjectNode(projectName, projectId);
    YoungAndroidAssetsFolder assetsNode = new YoungAndroidAssetsFolder("assets");
    YoungAndroidSourceFolderNode sourcesNode = new YoungAndroidSourceFolderNode("src");
    YoungAndroidComponentsFolder compsNode =
        new YoungAndroidComponentsFolder("assets/external_comps");
    root.addChild(assetsNode);
    root.addChild(sourcesNode);
    root.addChild(compsNode);

    Map<String, YoungAndroidPackageNode> packagesMap = new HashMap<>();
    String prefix = projectId + ":";
    for (String key : contents.keySet()) {
      if (!key.startsWith(prefix)) continue;
      String fileId = key.substring(prefix.length());
      if (fileId.startsWith("youngandroidproject/")) continue;
      if (fileId.startsWith("assets/external_comps/")) {
        compsNode.addChild(new YoungAndroidComponentNode(StorageUtil.basename(fileId), fileId));
      } else if (fileId.startsWith("assets/")) {
        assetsNode.addChild(new YoungAndroidAssetNode(StorageUtil.basename(fileId), fileId));
      } else if (fileId.startsWith("src/")) {
        YoungAndroidSourceNode sourceNode = null;
        if (fileId.endsWith(".scm")) {
          sourceNode = new YoungAndroidFormNode(fileId);
        } else if (fileId.endsWith(".bky")) {
          sourceNode = new YoungAndroidBlocksNode(fileId);
        } else if (fileId.endsWith(".yail")) {
          sourceNode = new YoungAndroidYailNode(fileId);
        }
        if (sourceNode != null) {
          String packageName = StorageUtil.getPackageName(sourceNode.getQualifiedName());
          YoungAndroidPackageNode packageNode = packagesMap.get(packageName);
          if (packageNode == null) {
            packageNode = new YoungAndroidPackageNode(packageName, packageNameToPath(packageName));
            packagesMap.put(packageName, packageNode);
            sourcesNode.addChild(packageNode);
          }
          packageNode.addChild(sourceNode);
        }
      }
    }
    return root;
  }

  /**
   * Serializes a metadata write to IDB. All calls go through {@link #persistChain} so
   * that rapid successive mutations are applied in order.
   */
  private Promise<Void> persistAsync() {
    String json = buildProjectsJson();
    final Promise<Void> next = persistChain.then0(
        () -> LocalIdbStore.putMeta(LocalIdbStore.PROJECTS_KEY, json));
    persistChain = next.error(err -> {
      // log but keep the chain alive so the next write attempt is not skipped
      return Promise.resolve(null);
    });
    return next;
  }

  /**
   * Stores imported content in memory even when the browser disallows
   * IndexedDB (notably for some file:// origins). The current session remains
   * usable; persistence is best effort in that environment.
   */
  private Promise<Void> persistContentBestEffort(String key, ArrayBuffer buffer) {
    contents.put(key, buffer);
    if (idbUnavailable) {
      return Promise.resolve(null);
    }
    return LocalIdbStore.putContent(key, buffer).error(error -> {
      idbUnavailable = true;
      ErrorReporter.reportInfo(
          "IndexedDB unavailable; imported project will only persist for this session.");
      return Promise.resolve(null);
    });
  }

  /** Persists project metadata without preventing an in-memory import from completing. */
  private Promise<Void> persistProjectsBestEffort() {
    if (idbUnavailable) {
      return Promise.resolve(null);
    }
    return persistAsync().error(error -> {
      idbUnavailable = true;
      ErrorReporter.reportInfo(
          "IndexedDB unavailable; imported project will only persist for this session.");
      return Promise.resolve(null);
    });
  }

  private String buildProjectsJson() {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    boolean first = true;
    for (Map.Entry<String, UserProject> e : projects.entrySet()) {
      UserProject p = e.getValue();
      if (!first) {
        sb.append(',');
      }
      first = false;
      sb.append("{\"id\":").append(p.getProjectId())
          .append(",\"name\":").append(jsonString(e.getKey()))
          .append(",\"type\":").append(jsonString(p.getProjectType()))
          .append(",\"created\":").append(p.getDateCreated())
          .append(",\"modified\":").append(p.getDateModified())
          .append('}');
    }
    sb.append(']');
    return sb.toString();
  }

  // -------- File content operations --------

  /**
   * Writes a file's bytes to IndexedDB and the in-memory cache.
   *
   * @param projectId the project owning the file
   * @param fileId the file path within the project (e.g. {@code assets/kitty.png})
   * @param buffer the file content
   * @return a promise resolving to the modification time
   */
  public Promise<Long> saveContent(long projectId, String fileId, ArrayBuffer buffer) {
    String key = projectId + ":" + fileId;
    // TextEncoder.encode is exposed as ArrayBuffer in the GWT wrapper but
    // returns a Uint8Array in browsers. Normalize both representations before
    // passing bytes to IndexedDB so legacy-template upgrades use a durable,
    // cloneable ArrayBuffer just like ZIP imports do.
    final ArrayBuffer stableBuffer = cloneBuffer(buffer);
    return ensureLoadedAsync()
        .then(v -> persistContentBestEffort(key, stableBuffer))
        .then(v -> {
          dataUrlCache.remove(key);
          return Promise.resolve(System.currentTimeMillis());
        });
  }

  public Promise<Long> saveContent(long projectId, String fileId, String source) {
    ArrayBuffer buffer = new TextEncoder("utf-8").encode(source);
    return saveContent(projectId, fileId, buffer);
  }

  /**
   * Deletes a file from IndexedDB and the in-memory cache.
   *
   * @param projectId the project owning the file
   * @param fileId the file path within the project (e.g. {@code assets/kitty.png})
   * @return a promise resolving to the modification time
   */
  public Promise<Long> deleteContent(long projectId, String fileId) {
    String key = projectId + ":" + fileId;
    return ensureLoadedAsync()
        .then(v -> LocalIdbStore.deleteContent(key))
        .then(v -> {
          contents.remove(key);
          dataUrlCache.remove(key);
          return Promise.resolve(System.currentTimeMillis());
        });
  }

  public Promise<String> getAssetDataUrl(long projectId, String fileId) {
    return getAssetDataUrl(projectId, fileId, MAX_PREVIEW_SIZE);
  }

  /** Resolves any stored project file to a data URL for an explicit download. */
  public Promise<String> getFileDataUrl(long projectId, String fileId) {
    String key = projectId + ":" + fileId;
    return ensureLoadedAsync().then(v -> {
      ArrayBuffer buffer = contents.get(key);
      if (buffer == null) {
        return Promise.resolve("");
      }
      String mime = StorageUtil.getContentTypeForFilePath(fileId);
      return Promise.resolve("data:" + mime + ";base64," + bufferToBase64(buffer));
    });
  }

  /**
   * Resolves a media file to a {@code data:} URL for preview rendering, or the
   * empty string when the file is not previewable, missing, or larger than
   * {@code maxSize}.
   *
   * @param projectId the project owning the file
   * @param fileId the file path within the project
   * @param maxSize maximum file size in bytes for which a data URL is produced
   * @return a promise resolving to the data URL, or {@code ""} when unavailable
   */
  public Promise<String> getAssetDataUrl(long projectId, String fileId, int maxSize) {
    String key = projectId + ":" + fileId;
    String mime = StorageUtil.getContentTypeForFilePath(fileId);
    if (!mime.startsWith("image/") && !mime.startsWith("audio/")
        && !mime.startsWith("video/") && !mime.startsWith("font/")) {
      return Promise.resolve("");
    }
    // Cache is only honored for the default (capped) path; the uncapped
    // preview-dialog path always regenerates to avoid the empty-string
    // sentinel from a prior size-limited call.
    if (maxSize == MAX_PREVIEW_SIZE && dataUrlCache.containsKey(key)) {
      return Promise.resolve(dataUrlCache.get(key));
    }
    return ensureLoadedAsync().then(v -> {
      ArrayBuffer buf = contents.get(key);
      if (buf == null) {
        return Promise.resolve("");
      }
      if (buf.byteLength() > maxSize) {
        return Promise.resolve("");
      }
      String dataUrl = "data:" + mime + ";base64," + bufferToBase64(buf);
      if (maxSize == MAX_PREVIEW_SIZE) {
        dataUrlCache.put(key, dataUrl);
      }
      return Promise.resolve(dataUrl);
    });
  }

  /**
   * Resolves a project asset to a {@code data:} URL synchronously, or returns
   * {@code null} when running against a real backend (not {@code local.services}),
   * when the asset is missing/not image- or font-like, or when it exceeds the
   * preview size cap. Used by the Designer's mock rendering, which needs a URL
   * string immediately and cannot wait on an async IndexedDB read. Returns null
   * (never throws) so callers can fall back to the server URL in non-local mode.
   */
  public static String getLocalAssetDataUrl(long projectId, String fileId) {
    ProjectServiceAsync svc = Ode.getInstance().getProjectService();
    if (!(svc instanceof LocalProjectService)) {
      return null;
    }
    return ((LocalProjectService) svc).assetToDataUrl(projectId, fileId);
  }

  private String assetToDataUrl(long projectId, String fileId) {
    String key = projectId + ":" + fileId;
    String mime = StorageUtil.getContentTypeForFilePath(fileId);
    if (!mime.startsWith("image/") && !mime.startsWith("font/")) {
      return null;
    }
    ArrayBuffer buf = contents.get(key);
    if (buf == null) {
      return null;
    }
    if (buf.byteLength() > MAX_PREVIEW_SIZE) {
      return null;
    }
    String cached = dataUrlCache.get(key);
    if (cached != null) {
      return cached;
    }
    String dataUrl = "data:" + mime + ";base64," + bufferToBase64(buf);
    dataUrlCache.put(key, dataUrl);
    return dataUrl;
  }

  /**
   * Downloads a file from {@code url} and stores it as an asset of the project.
   *
   * @param projectId the target project
   * @param url the source URL to fetch
   * @return a promise resolving to the imported {@link TextFile}
   */
  public Promise<TextFile> importMediaFromUrl(long projectId, String url) {
    String fileName = basenameFromUrl(url);
    String fileId = "assets/" + fileName;
    return ensureLoadedAsync()
        .then0(() -> fetchUrlAsBuffer(url))
        .then(buffer -> saveContent(projectId, fileId, buffer)
            .then(date -> Promise.resolve(new TextFile(fileName, bufferToBase64(buffer)))));
  }

  // -------- Original service methods, with persistence wired in --------

  @Override
  public void newProject(String projectType, String projectName, NewProjectParameters params,
      AsyncCallback<UserProject> callback) {
    newProjectImpl(projectType, projectName, (NewYoungAndroidProjectParameters) params, callback);
  }

  private void newProjectImpl(String projectType, String projectName,
      NewYoungAndroidProjectParameters youngAndroidParams, AsyncCallback<UserProject> callback) {
    long hash = projectName.hashCode();
    long now = System.currentTimeMillis();
    final UserProject project = new UserProject(hash, projectName, projectType, now, now);
    YoungAndroidProjectNode root = new YoungAndroidProjectNode(projectName, hash);
    YoungAndroidAssetsFolder assetsNode = new YoungAndroidAssetsFolder("assets");
    YoungAndroidSourceFolderNode sourcesNode = new YoungAndroidSourceFolderNode("src");
    YoungAndroidComponentsFolder compsNode =
        new YoungAndroidComponentsFolder("assets/external_comps");
    root.addChild(assetsNode);
    root.addChild(sourcesNode);
    root.addChild(compsNode);

    String packageName = youngAndroidParams.getPackageName();
    YoungAndroidPackageNode packageNode = new YoungAndroidPackageNode(packageName,
        packageNameToPath(packageName));
    sourcesNode.addChild(packageNode);

    String qualifiedName = youngAndroidParams.getQualifiedFormName();
    String formFileName = YoungAndroidFormNode.getFormFileId(qualifiedName);
    String formFileContents = getInitialFormPropertiesFileContents(qualifiedName, youngAndroidParams);
    String blocksFileName = YoungAndroidBlocksNode.getBlocklyFileId(qualifiedName);
    String projectPropertiesFileName = "youngandroidproject/project.properties";
    String projectPropertiesFileContents = "sizing=Responsive\n" +
        "color.primary.dark=&HFF303F9F\n" +
        "color.primary=&HFF3F51B5\n" +
        "color.accent=&HFFFF4081\n" +
        "aname=rl_maze\n" +
        "defaultfilescope=App\n" +
        "main=" + qualifiedName + ".Screen1\n" +
        "source=../src\n" +
        "actionbar=True\n" +
        "projectcolors={}\n" +
        "useslocation=False\n" +
        "assets=../assets\n" +
        "build=../build\n" +
        "name=" + projectName + "\n" +
        "showlistsasjson=True\n" +
        "theme=AppTheme.Light\n" +
        "versioncode=1\n" +
        "versionname=1.0\n";

    packageNode.addChild(new YoungAndroidFormNode(formFileName));
    packageNode.addChild(new YoungAndroidBlocksNode(blocksFileName));

    Promise<Long> write1 = saveContent(hash, formFileName, formFileContents);
    Promise<Long> write2 = saveContent(hash, blocksFileName, "");
    Promise<Long> write3 = saveContent(hash, projectPropertiesFileName,
        projectPropertiesFileContents);

    ensureLoadedAsync()
        .then0(() -> Promise.allOf(write1, write2, write3))
        .then(v -> {
          projects.put(projectName, project);
          projectData.put(hash, root);
          return persistAsync();
        })
        .then(v -> {
          callback.onSuccess(project);
          return Promise.resolve(null);
        })
        .error(err -> {
          callback.onFailure(err);
          return null;
        });
  }

  /**
   * Returns the initial {@code .scm} contents for a new form, mirroring what the
   * online build server writes so the Designer renders identically in offline mode.
   */
  public static String getInitialFormPropertiesFileContents(String qualifiedName,
      NewYoungAndroidProjectParameters youngAndroidParams) {
    final int lastDotPos = qualifiedName.lastIndexOf('.');
    String packageName = qualifiedName.split("\\.")[2];
    String formName = qualifiedName.substring(lastDotPos + 1);
    String themeName = youngAndroidParams.getThemeName();
    String blocksToolkit = youngAndroidParams.getBlocksToolkit();

    String newString = "#|\n$JSON\n" +
        "{\"authURL\":[]," +
        "\"YaVersion\":\"" + YaVersion.YOUNG_ANDROID_VERSION + "\",\"Source\":\"Form\"," +
        "\"Properties\":{\"$Name\":\"" + formName + "\",\"$Type\":\"Form\"," +
        "\"$Version\":\"" + YaVersion.FORM_COMPONENT_VERSION + "\",\"Uuid\":\"" + 0 + "\"," +
        "\"Title\":\"" + formName + "\",\"AppName\":\"" + packageName +"\",\"Theme\":\"" +
        themeName + "\"}}\n|#";
    if (!blocksToolkit.isEmpty()){
      newString = "#|\n$JSON\n" +
          "{\"authURL\":[]," +
          "\"YaVersion\":\"" + YaVersion.YOUNG_ANDROID_VERSION + "\",\"Source\":\"Form\"," +
          "\"Properties\":{\"$Name\":\"" + formName + "\",\"$Type\":\"Form\"," +
          "\"$Version\":\"" + YaVersion.FORM_COMPONENT_VERSION + "\",\"Uuid\":\"" + 0 + "\"," +
          "\"Title\":\"" + formName + "\",\"AppName\":\"" + packageName +"\",\"Theme\":\"" +
          themeName +  "\",\"BlocksToolkit\":" + JSONUtil.toJson(blocksToolkit) +"}}\n|#";
    }
    return newString;
  }

  @Override
  public void newProjectFromTemplate(String projectName, String pathToZip,
      AsyncCallback<UserProject> callback) {
    // Built-in templates are packaged beside the static offline entry point.
    // Read the ZIP locally and use the same importer as user-selected .aia files.
    fetchTemplateArchive(pathToZip)
        .then(buffer -> {
          newProjectFromExternalTemplate(projectName, bufferToBase64(buffer), callback);
          return Promise.resolve(null);
        })
        .error(error -> {
          callback.onFailure(new RuntimeException(
              "Unable to load packaged template archive: " + pathToZip, error));
          return null;
        });
  }

  private static String packageNameToPath(String packageName) {
    return "src/" + packageName.replace('.', '/');
  }

  @Override
  public void newProjectFromExternalTemplate(String projectName, String zipData,
      AsyncCallback<UserProject> callback) {
    if (projectName == null || projectName.isEmpty()) {
      callback.onFailure(new IllegalArgumentException("Template project name is empty"));
      return;
    }
    if (zipData == null || zipData.isEmpty()) {
      callback.onFailure(new IllegalArgumentException(
          "Template archive is empty: " + projectName));
      return;
    }

    ensureLoadedAsync()
        .then0(() -> {
          final long hash = projectName.hashCode();
          final long now = System.currentTimeMillis();
          final UserProject project = new UserProject(hash, projectName, "YoungAndroid", now, now);
          final YoungAndroidProjectNode root = new YoungAndroidProjectNode(projectName, hash);
          final YoungAndroidAssetsFolder assetsNode = new YoungAndroidAssetsFolder("assets");
          final YoungAndroidSourceFolderNode sourcesNode = new YoungAndroidSourceFolderNode("src");
          final YoungAndroidComponentsFolder compsNode =
              new YoungAndroidComponentsFolder("assets/external_comps");
          root.addChild(assetsNode);
          root.addChild(sourcesNode);
          root.addChild(compsNode);

          final JSZip zip = new JSZip();
          // Use the JSZip instance returned by loadAsync. This is important for
          // JSZip builds where loadAsync returns a populated clone rather than
          // mutating the receiver.
          return zip.loadAsync(zipData, LoadOptions.create(true))
              .then(loadedZip -> {
                final Map<String, ProjectNode> packagesMap = new HashMap<>();
                final List<Promise> idbWrites = new ArrayList<>();
                final int[] fileCount = new int[1];
                loadedZip.forEach((name, zipObject) -> {
                  if (name.endsWith("/")) {
                    return;
                  }
                  fileCount[0]++;
                  final String key = hash + ":" + name;
                  idbWrites.add(zipObject.get(Type.ARRAY_BUFFER).then(buffer ->
                      persistContentBestEffort(key, buffer)));
                  if (name.startsWith("assets/")) {
                    if (name.startsWith("assets/external_comps/")) {
                      compsNode.addChild(new YoungAndroidComponentNode(
                          StorageUtil.basename(name), name));
                    } else {
                      assetsNode.addChild(new YoungAndroidAssetNode(
                          StorageUtil.basename(name), name));
                    }
                  } else if (name.startsWith("src/")) {
                    YoungAndroidSourceNode sourceNode = null;
                    if (name.endsWith(".scm")) {
                      sourceNode = new YoungAndroidFormNode(name);
                    } else if (name.endsWith(".bky")) {
                      sourceNode = new YoungAndroidBlocksNode(name);
                    } else if (name.endsWith(".yail")) {
                      sourceNode = new YoungAndroidYailNode(name);
                    }
                    if (sourceNode != null) {
                      String packageName = StorageUtil.getPackageName(sourceNode.getQualifiedName());
                      ProjectNode packageNode = packagesMap.get(packageName);
                      if (packageNode == null) {
                        packageNode = new YoungAndroidPackageNode(
                            packageName, packageNameToPath(packageName));
                        packagesMap.put(packageName, packageNode);
                        sourcesNode.addChild(packageNode);
                      }
                      packageNode.addChild(sourceNode);
                    }
                  }
                });
                if (fileCount[0] == 0) {
                  return Promise.reject(new IllegalArgumentException(
                      "Template archive contains no files: " + projectName));
                }
                return Promise.allOf(idbWrites.toArray(new Promise[0]))
                    .then0(() -> {
                      projects.put(projectName, project);
                      projectData.put(hash, root);
                      return persistProjectsBestEffort();
                    });
              })
              .then(v -> {
                callback.onSuccess(project);
                return Promise.resolve(null);
              });
        })
        .error(err -> {
          callback.onFailure(err);
          return null;
        });
  }

  @Override
  public void retrieveTemplateData(String pathToTemplates, AsyncCallback<String> callback) {
    String manifestPath = pathToTemplates.endsWith("templates.json")
        ? pathToTemplates
        : pathToTemplates + "templates.json";
    fetchUrlAsText(manifestPath)
        .then(json -> {
          if (json == null || json.trim().isEmpty()) {
            callback.onFailure(new RuntimeException(
                "Unable to load packaged template manifest: " + manifestPath));
          } else {
            callback.onSuccess(json);
          }
          return Promise.resolve(null);
        })
        .error(error -> {
          // Chrome/WebView may reject file-origin XHR even though the static
          // artifact itself is valid. Keep startup and the built-in repository
          // usable with the embedded manifest in that case.
          if (manifestPath.contains("templates.json")) {
            callback.onSuccess(EMBEDDED_TEMPLATE_MANIFEST);
          } else {
            callback.onFailure(new RuntimeException(
                "Unable to load packaged template manifest: " + manifestPath, error));
          }
          return null;
        });
  }

  @Override
  public void copyProject(long oldProjectId, String newName, AsyncCallback<UserProject> callback) {
    ensureLoadedAsync().then0(() -> {
      String oldName = null;
      for (Map.Entry<String, UserProject> e : projects.entrySet()) {
        if (e.getValue().getProjectId() == oldProjectId) {
          oldName = e.getKey();
          break;
        }
      }
      if (oldName == null) {
        callback.onFailure(new Exception("Project not found"));
        return Promise.resolve(null);
      }
      long newHash = newName.hashCode();
      long now = System.currentTimeMillis();
      UserProject copy = new UserProject(newHash, newName, "YoungAndroid", now, now);
      projects.put(newName, copy);
      String prefix = oldProjectId + ":";
      String newPrefix = newHash + ":";
      List<Promise<Void>> writes = new ArrayList<>();
      for (Map.Entry<String, ArrayBuffer> e : new ArrayList<>(contents.entrySet())) {
        if (e.getKey().startsWith(prefix)) {
          String newKey = newPrefix + e.getKey().substring(prefix.length());
          contents.put(newKey, e.getValue());
          writes.add(LocalIdbStore.putContent(newKey, e.getValue()));
        }
      }
      Promise.allOf(writes.toArray(new Promise[0]))
          .then(v -> {
            projectData.put(newHash, reconstructTree(newHash, newName));
            return persistAsync();
          })
          .then(v -> {
            callback.onSuccess(copy);
            return Promise.resolve(null);
          })
          .error(err -> {
            callback.onFailure(err);
            return null;
          });
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  public void renameProjects(List<Long> projectIds, List<String> projectNames,
      AsyncCallback<Void> callback) {
    ensureLoadedAsync().then0(() -> {
      if (projectIds.size() != projectNames.size()) {
        callback.onFailure(new IllegalArgumentException("size mismatch"));
        return Promise.resolve(null);
      }
      long now = System.currentTimeMillis();
      List<UserProject> toRemove = new ArrayList<>();
      for (int i = 0; i < projectIds.size(); i++) {
        long id = projectIds.get(i);
        String newName = projectNames.get(i);
        for (Map.Entry<String, UserProject> e : projects.entrySet()) {
          if (e.getValue().getProjectId() == id) {
            UserProject p = e.getValue();
            toRemove.add(p);
            projects.put(newName, new UserProject(id, newName, p.getProjectType(),
                p.getDateCreated(), now));
            break;
          }
        }
      }
      for (UserProject p : toRemove) {
        for (Map.Entry<String, UserProject> e : projects.entrySet()) {
          if (e.getValue() == p) {
            projects.remove(e.getKey());
            break;
          }
        }
      }
      persistAsync()
          .then(v -> {
            callback.onSuccess(null);
            return Promise.resolve(null);
          })
          .error(err -> {
            callback.onFailure(err);
            return null;
          });
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void loginToGallery(AsyncCallback<RpcResult> callback) {
  }

  @Override
  public void sendToGallery(long projectId, AsyncCallback<RpcResult> callback) {
  }

  @Override
  public void loadFromGallery(String galleryId, AsyncCallback<UserProject> callback) {
  }

  @Override
  public void deleteProject(long projectId, AsyncCallback<Void> callback) {
    ensureLoadedAsync().then0(() -> {
      String removeName = null;
      for (Map.Entry<String, UserProject> e : projects.entrySet()) {
        if (e.getValue().getProjectId() == projectId) {
          removeName = e.getKey();
          break;
        }
      }
      if (removeName != null) {
        projects.remove(removeName);
      }
      String prefix = projectId + ":";
      List<Promise<Void>> writes = new ArrayList<>();
      List<String> toRemove = new ArrayList<>();
      for (String key : contents.keySet()) {
        if (key.startsWith(prefix)) {
          toRemove.add(key);
        }
      }
      for (String key : toRemove) {
        contents.remove(key);
        dataUrlCache.remove(key);
        writes.add(LocalIdbStore.deleteContent(key));
      }
      projectData.remove(projectId);
      Promise.allOf(writes.toArray(new Promise[0]))
          .then(v -> persistAsync())
          .then(v -> {
            callback.onSuccess(null);
            return Promise.resolve(null);
          })
          .error(err -> {
            callback.onFailure(err);
            return null;
          });
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void getProjects(AsyncCallback<long[]> callback) {
    ensureLoadedAsync().then0(() -> {
      long[] ids = new long[projects.size()];
      int i = 0;
      for (UserProject p : projects.values()) {
        ids[i++] = p.getProjectId();
      }
      callback.onSuccess(ids);
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void getProjectInfos(AsyncCallback<List<UserProject>> callback) {
    ensureLoadedAsync().then0(() -> {
      callback.onSuccess(new ArrayList<>(projects.values()));
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void getProject(long projectId, AsyncCallback<ProjectRootNode> callback) {
    ensureLoadedAsync().then0(() -> {
      callback.onSuccess(projectData.get(projectId));
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void loadProjectSettings(long projectId, AsyncCallback<String> callback) {
    ensureLoadedAsync().then0(() -> {
      ArrayBuffer contentsBuf = contents.get(projectId + ":youngandroidproject/project.properties");
      if (contentsBuf == null) {
        callback.onFailure(new Exception("File not found"));
        return Promise.resolve(null);
      }
      TextDecoder decoder = new TextDecoder("utf-8");
      String content = decoder.decode(contentsBuf);
      try {
        String[] lines = content.split("\n");
        Map<String, String> properties = new HashMap<>();
        for (String line : lines) {
          String[] parts = line.split("=");
          if (parts.length == 2) {
            properties.put(parts[0].trim(), parts[1].trim());
          }
        }

        ProjectSettings settings = new ProjectSettings(
            Ode.getInstance().getProjectManager().getProject(projectId));
        YoungAndroidSettings child = (YoungAndroidSettings) settings.getSettings(SettingsConstants.PROJECT_YOUNG_ANDROID_SETTINGS);
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_ICON, properties, "icon");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_VERSION_CODE, properties, "versioncode");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_VERSION_NAME, properties, "versionname");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_USES_LOCATION, properties, "useslocation");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_APP_NAME, properties, "aname");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_SIZING, properties, "sizing");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_SHOW_LISTS_AS_JSON, properties, "showlistsasjson");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_TUTORIAL_URL, properties, "tutorialurl");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_BLOCK_SUBSET, properties, "subsetjson");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_ACTIONBAR, properties, "actionbar");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_THEME, properties, "theme");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_PRIMARY_COLOR, properties, "color.primary");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_PRIMARY_COLOR_DARK, properties, "color.primary.dark");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_ACCENT_COLOR, properties, "color.accent");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_DEFAULTFILESCOPE, properties, "defaultfilescope");
        setPropertyIfPresent(child, YOUNG_ANDROID_SETTINGS_PROJECT_COLORS, properties, "projectcolors");
        callback.onSuccess(settings.encodeSettings());
      } catch (Exception e) {
        callback.onFailure(e);
      }
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  private static void setPropertyIfPresent(YoungAndroidSettings settings, String propertyName,
      Map<String, String> properties, String sourceName) {
    if (properties.containsKey(sourceName)) {
      settings.changePropertyValue(propertyName, properties.get(sourceName));
    }
  }

  @Override
  public void storeProjectSettings(String sessionId, long projectId, String settings,
      AsyncCallback<Void> callback) {
    callback.onSuccess(null);
  }

  @Override
  public void deleteFile(String sessionId, long projectId, String fileId,
      AsyncCallback<Long> callback) {
    deleteContent(projectId, fileId)
        .then(date -> {
          callback.onSuccess(date);
          return Promise.resolve(null);
        })
        .error(err -> {
          callback.onFailure(err);
          return null;
        });
  }

  @Override
  public void deleteFiles(String sessionId, long projectId, String directory,
      AsyncCallback<Long> callback) {
    // Not needed by the current Designer workflows; out of scope for offline mode.
    callback.onSuccess(System.currentTimeMillis());
  }

  @Override
  public void deleteFolder(String sessionId, long projectId, String directory,
      AsyncCallback<Long> callback) {
    callback.onSuccess(System.currentTimeMillis());
  }

  @Override
  public void load(long projectId, String fileId, AsyncCallback<String> callback) {
  }

  @Override
  public void loadDataFile(long projectId, String fileId,
      AsyncCallback<List<List<String>>> callback) {
  }

  @Override
  public void load2(long projectId, String fileId, final AsyncCallback<ChecksumedLoadFile> callback) {
    ensureLoadedAsync().then0(() -> {
      ArrayBuffer buffer = contents.get(projectId + ":" + fileId);
      if (buffer == null) {
        callback.onFailure(new Exception("File not found"));
        return Promise.resolve(null);
      }
      TextDecoder decoder = new TextDecoder("utf-8");
      String content = decoder.decode(buffer);
      final ChecksumedLoadFile file = new ChecksumedLoadFile();
      try {
        file.setContent(content);
      } catch (Exception e) {
        callback.onFailure(e);
        return Promise.resolve(null);
      }
      callback.onSuccess(file);
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void recordCorruption(long ProjectId, String fileId, String message,
      AsyncCallback<Void> callback) {
  }

  @Override
  public void loadraw(long projectId, String fileId, AsyncCallback<byte[]> callback) {
  }

  @Override
  public void loadraw2(long projectId, String fileId, AsyncCallback<String> callback) {
    // Plan D 修复:之前是空实现,offline-webapp 模式下 AssetManager 的 readIn
    // 永远拿不到回调,progress bar 卡在 "Downloading assets/ from the App Inventor
    // server..."。从 IndexedDB-backed contents Map 读取 ArrayBuffer 并以纯 base64
    // 返回(供 Base64Util.decodeLines 解码)。
    String key = projectId + ":" + fileId;
    ensureLoadedAsync().then(v -> {
      ArrayBuffer buf = contents.get(key);
      if (buf == null) {
        callback.onFailure(new RuntimeException("Asset not found: " + fileId));
        return Promise.resolve(null);
      }
      callback.onSuccess(bufferToBase64(buf));
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  /**
   * Plan E step 2: read a project's file bytes (any fileId) as a raw ArrayBuffer.
   * Used by {@code replmgr.js} to enumerate and push extension files (classes.jar,
   * components.json, ...) to the AI Companion before sending
   * {@code AssetFetcher:loadExtensions}. Unlike {@link #load2}, this does NOT
   * UTF-8 decode — binary content (classes.jar, icons) round-trips intact.
   *
   * <p>Exported to JavaScript via {@code LocalProjectService_nativeBridge}
   * (initialized by {@link #exportNativeBridge()}).
   *
   * @param projectId owning project (passed as double from JSNI; cast to long)
   * @param fileId    e.g. {@code assets/external_comps/<pkg>/classes.jar}
   * @return ArrayBuffer or {@code null} when the file is missing
   */
  /** Returns all non-directory files for a project as a JavaScript object map. */
  public Promise<JavaScriptObject> exportProjectFiles(long projectId) {
    return ensureLoadedAsync().then(v -> {
      final JavaScriptObject result = createObject();
      String prefix = projectId + ":";
      for (Map.Entry<String, ArrayBuffer> entry : contents.entrySet()) {
        if (entry.getKey().startsWith(prefix)) {
          String fileId = entry.getKey().substring(prefix.length());
          if (!fileId.endsWith("/")) {
            putObjectValue(result, fileId, entry.getValue());
          }
        }
      }
      return Promise.resolve(result);
    });
  }

  public static ArrayBuffer getFileBytes(double projectId, String fileId) {
    LocalProjectService svc = singleton();
    if (svc == null) {
      return null;
    }
    String key = ((long) projectId) + ":" + fileId;
    return svc.contents.get(key);
  }

  public static JsArrayString getExtensionFileIds(double projectId, String extensionName) {
    JsArrayString result = JsArrayString.createArray().cast();
    LocalProjectService svc = singleton();
    if (svc == null) {
      return result;
    }
    // LocalComponentService.importToProject writes files at
    //   "<folderPath>/external_comps/<extensionName>/<file>"
    // with the extensionName verbatim (dots, not slashes — matches the .aix
    // zip entry names and the format Companion's ReplForm.loadComponents
    // checks on disk). Iterate the contents map directly: the ProjectRootNode
    // tree is populated only on initial load, not when LocalComponentService
    // imports a new extension, so walking projectData would miss fresh files.
    String keyPrefix = ((long) projectId) + ":assets/external_comps/" + extensionName + "/";
    int stripLen = String.valueOf((long) projectId).length() + 1;
    // 先清理历史版本残留的目录占位条目(以 / 结尾的 key)——旧版
    // LocalComponentService 没过滤 JSZip 目录条目,这些空条目在 contents
    // 里,getExtensionFileIds 会返回目录路径,putAsset 的 Files:write 会失败。
    java.util.Iterator<String> it = svc.contents.keySet().iterator();
    while (it.hasNext()) {
      String k = it.next();
      if (k.startsWith(keyPrefix) && k.substring(stripLen).endsWith("/")) {
        it.remove();
      }
    }
    for (String key : svc.contents.keySet()) {
      if (key.startsWith(keyPrefix)) {
        String fileId = key.substring(stripLen);
        // 防御性:再次过滤目录占位(迭代中已删,这里保底)。
        if (fileId.endsWith("/")) {
          continue;
        }
        result.push(fileId);
      }
    }
    return result;
  }

  private static native JavaScriptObject createObject() /*-{
    return {};
  }-*/;

  private static native void putObjectValue(JavaScriptObject object, String key,
      ArrayBuffer value) /*-{
    object[key] = value;
  }-*/;

  /** Returns the singleton LocalProjectService instance, or null if none. */
  private static LocalProjectService singleton() {
    ProjectServiceAsync svc = Ode.getInstance().getProjectService();
    return (svc instanceof LocalProjectService) ? (LocalProjectService) svc : null;
  }

  /**
   * JSNI bridge: install {@code top.LocalProjectService_getFileBytes} and
   * {@code top.LocalProjectService_getExtensionFileIds} so {@code replmgr.js}
   * can enumerate and read extension files for Companion push (Plan E step 2).
   */
  public static native void exportNativeBridge() /*-{
    var self = this;
    var getFileBytes = $entry(function(projectId, fileId) {
      return @com.google.appinventor.client.local.LocalProjectService::getFileBytes(DLjava/lang/String;)(projectId, fileId);
    });
    var getExtensionFileIds = $entry(function(projectId, extensionName) {
      return @com.google.appinventor.client.local.LocalProjectService::getExtensionFileIds(DLjava/lang/String;)(projectId, extensionName);
    });
    // 同时注册到 $wnd(JSNI 内的 GWT iframe)和 top(外层 page),因为:
    // - replmgr.js 的 _pushExtensionFiles 在 top 上下文(外层 page 的 <script> 标签加载)读 top.LocalProjectService_*
    // - GWT Java 端的 $wnd 是 GWT bootstrap iframe 的 window,跟 top 不同
    // 老代码只注册 $wnd,导致 _pushExtensionFiles 看到的 bridge 是 undefined,
    // 直接 fall through 到 _finishLoadExtensions,跳过 push,Companion 找不到 classes.jar。
    $wnd.LocalProjectService_getFileBytes = getFileBytes;
    $wnd.LocalProjectService_getExtensionFileIds = getExtensionFileIds;
    if ($wnd.top && $wnd.top !== $wnd) {
      $wnd.top.LocalProjectService_getFileBytes = getFileBytes;
      $wnd.top.LocalProjectService_getExtensionFileIds = getExtensionFileIds;
    }
  }-*/;

  @Override
  public void load(List<FileDescriptor> files,
      AsyncCallback<List<FileDescriptorWithContent>> callback) {
  }

  @Override
  public void save(String sessionId, long projectId, String fileId, String source,
      AsyncCallback<Long> callback) {
    save2(sessionId, projectId, fileId, false, source, callback);
  }

  @Override
  public void save2(String sessionId, long projectId, String fileId, boolean force, String source,
      AsyncCallback<Long> callback) {
    saveContent(projectId, fileId, source)
        .then(date -> {
          callback.onSuccess(date);
          return Promise.resolve(null);
        })
        .error(err -> {
          // Local saves are in-memory first and persistence is best effort.
          // Do not pass a raw browser rejection through OdeAsyncCallback: GWT's
          // legacy Throwable logger cannot format native JavaScript errors and
          // turns a harmless storage failure into "a.sb is not a function".
          ErrorReporter.reportInfo("Unable to persist local file " + fileId
              + "; keeping the change for this session.");
          callback.onSuccess(System.currentTimeMillis());
          return null;
        });
  }

  @Override
  public void save(String sessionId, List<FileDescriptorWithContent> filesAndContent,
      AsyncCallback<Long> callback) {
    ensureLoadedAsync().then0(() -> {
      List<Promise<Long>> writes = new ArrayList<>();
      for (FileDescriptorWithContent file : filesAndContent) {
        writes.add(saveContent(file.getProjectId(), file.getFileId(), file.getContent()));
      }
      Promise.allOf(writes.toArray(new Promise[0]))
          .then(v -> {
            long now = System.currentTimeMillis();
            callback.onSuccess(now);
            return Promise.resolve(null);
          })
          .error(err -> {
            callback.onFailure(err);
            return null;
          });
      return Promise.resolve(null);
    }).error(err -> {
      callback.onFailure(err);
      return null;
    });
  }

  @Override
  public void screenshot(String sessionId, long projectId, String fileId, String content,
      AsyncCallback<RpcResult> callback) {
  }

  @Override
  public void build(long projectId, String nonce, String target, boolean secondBuildserver,
      boolean isAab, boolean foriOS, boolean forAppStore, AsyncCallback<RpcResult> callback) {
  }

  @Override
  public void getBuildResult(long projectId, String target, AsyncCallback<RpcResult> callback) {
  }

  @Override
  public void addFile(long projectId, String fileId, AsyncCallback<Long> callback) {
    // Not used by media upload paths.
    callback.onSuccess(System.currentTimeMillis());
  }

  @Override
  public void importMedia(String sessionId, long projectId, String url, boolean save,
      AsyncCallback<TextFile> odeAsyncCallback) {
    importMediaFromUrl(projectId, url)
        .then(tf -> {
          odeAsyncCallback.onSuccess(tf);
          return Promise.resolve(null);
        })
        .error(err -> {
          odeAsyncCallback.onFailure(err);
          return null;
        });
  }

  @Override
  public void log(String message, AsyncCallback<Void> callback) {
  }

  /** JSNI-safe entry point for exporting a project id received as a JavaScript number. */
  public Promise<String> exportProjectFromJavaScript(double projectId) {
    return exportProject((long) projectId);
  }

  public Promise<String> exportProject(long projectId) {
    return ensureLoadedAsync().then0(() -> {
      if (!hasProject(projectId)) {
        return Promise.reject(new IllegalArgumentException(
            "Unable to export project: project " + projectId + " was not found"));
      }
      JSZip zip = new JSZip();
      String prefix = projectId + ":";
      int fileCount = 0;
      for (String key : contents.keySet()) {
        if (key.startsWith(prefix)) {
          String name = key.substring(prefix.length());
          if (!name.endsWith("/")) {
            // Clone the buffer before handing it to JSZip. The IndexedDB-backed
            // buffer can be reused by later reads; JSZip must receive a stable
            // binary snapshot for CRC calculation and compression.
            putZipFileBase64(zip, name, bufferToBase64(cloneBuffer(contents.get(key))));
            fileCount++;
          }
        }
      }
      if (fileCount == 0) {
        return Promise.reject(new IllegalArgumentException(
            "Unable to export project: project " + projectId + " has no files"));
      }
      return zip.generateAsync(GenerateOptions.create(Type.BASE64));
    });
  }

  /** Returns true when the local project metadata contains the given id. */
  private boolean hasProject(long projectId) {
    for (UserProject project : projects.values()) {
      if (project.getProjectId() == projectId) {
        return true;
      }
    }
    return false;
  }

  /**
   * Exports several projects into one outer archive. Each project is stored as
   * a complete {@code .aia} archive, matching the online multi-project export
   * format and preventing file-path collisions between projects.
   */
  public Promise<String> exportProjects(List<Long> projectIds) {
    return ensureLoadedAsync().then0(() -> {
      if (projectIds == null || projectIds.isEmpty()) {
        return Promise.reject(new IllegalArgumentException(
            "Unable to export projects: no projects were selected"));
      }
      final JSZip outerZip = new JSZip();
      List<Promise<String>> projectExports = new ArrayList<>();
      for (Long projectIdValue : projectIds) {
        if (projectIdValue == null || !hasProject(projectIdValue.longValue())) {
          return Promise.reject(new IllegalArgumentException(
              "Unable to export selected projects: project " + projectIdValue
                  + " was not found"));
        }
        final long projectId = projectIdValue.longValue();
        final String projectName = getProjectName(String.valueOf(projectId));
        if (projectName == null || projectName.isEmpty()) {
          return Promise.reject(new IllegalArgumentException(
              "Unable to export selected projects: project " + projectId
                  + " has no name"));
        }
        projectExports.add(exportProject(projectId).then(projectZip -> {
          putZipFileBase64(outerZip, projectName + ".aia", projectZip);
          return Promise.resolve(projectZip);
        }));
      }
      return Promise.allOf(projectExports.toArray(new Promise[0]))
          .then(ignored -> outerZip.generateAsync(GenerateOptions.create(Type.BASE64)));
    });
  }

  /** Exports every locally known project into one outer archive. */
  public Promise<String> exportAllProjects() {
    return ensureLoadedAsync().then(v -> {
      List<Long> projectIds = new ArrayList<>();
      for (UserProject project : projects.values()) {
        projectIds.add(project.getProjectId());
      }
      return exportProjects(projectIds);
    });
  }

  private static native ArrayBuffer cloneBuffer(ArrayBuffer buffer) /*-{
    var source = new Uint8Array(buffer);
    var copy = new Uint8Array(source.length);
    copy.set(source);
    return copy.buffer;
  }-*/;

  /** Add every file as a Base64 string using JSZip's explicit base64 mode. */
  private static native void putZipFileBase64(JSZip zip, String name, String base64) /*-{
    zip.file(name, base64, {base64: true});
  }-*/;

  // -------- JSON / binary helpers --------

  private static String jsonString(String s) {
    if (s == null) {
      return "\"\"";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append("\\u");
            String hex = Integer.toHexString(c);
            for (int k = hex.length(); k < 4; k++) {
              sb.append('0');
            }
            sb.append(hex);
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
    return sb.toString();
  }

  private static native ArrayBuffer base64ToBuffer(String b64) /*-{
    var bin = $wnd.atob(b64);
    var len = bin.length;
    var buf = new ArrayBuffer(len);
    var view = new Uint8Array(buf);
    for (var i = 0; i < len; i++) view[i] = bin.charCodeAt(i) & 0xff;
    return buf;
  }-*/;

  static native String bufferToBase64(ArrayBuffer buffer) /*-{
    var view = new Uint8Array(buffer);
    var bin = '';
    for (var i = 0; i < view.length; i++) bin += String.fromCharCode(view[i]);
    return $wnd.btoa(bin);
  }-*/;

  /** Loads a packaged template archive, using the generated file-origin-safe bundle first. */
  private static native Promise<ArrayBuffer> fetchTemplateArchive(String url) /*-{
    return new Promise(function(resolve, reject) {
      try {
        var match = String(url).match(/templates\/([^/]+)\/[^/]+\.zip$/);
        var topWindow = $wnd.top || $wnd;
        var bundled = topWindow.__AI2_OFFLINE_TEMPLATES__ || $wnd.__AI2_OFFLINE_TEMPLATES__;
        if (match && bundled && bundled[match[1]]) {
          var binary = $wnd.atob(bundled[match[1]]);
          var buffer = new ArrayBuffer(binary.length);
          var view = new Uint8Array(buffer);
          for (var i = 0; i < binary.length; i++) {
            view[i] = binary.charCodeAt(i) & 0xff;
          }
          resolve(buffer);
          return;
        }
      } catch (e) {
        // Fall through to XHR so a malformed optional bundle has a useful
        // missing-resource error instead of breaking application startup.
      }
      var xhr = new XMLHttpRequest();
      var resolved = new URL(url, $wnd.top.location.href).href;
      xhr.open('GET', resolved, true);
      xhr.responseType = 'arraybuffer';
      xhr.onload = function() {
        if ((xhr.status === 200 || xhr.status === 0) && xhr.response) {
          resolve(xhr.response);
        } else {
          reject(new Error('fetch failed: HTTP ' + xhr.status + ' (' + url + ')'));
        }
      };
      xhr.onerror = function() { reject(new Error('fetch failed: ' + url)); };
      xhr.send();
    });
  }-*/;

  private static native Promise<ArrayBuffer> fetchUrlAsBuffer(String url) /*-{
    return new Promise(function(resolve, reject) {
      var xhr = new XMLHttpRequest();
      var resolved = new URL(url, $wnd.top.location.href).href;
      xhr.open('GET', resolved, true);
      xhr.responseType = 'arraybuffer';
      xhr.onload  = function() {
        if ((xhr.status === 200 || xhr.status === 0) && xhr.response) {
          resolve(xhr.response);
        } else {
          reject(new Error('fetch failed: HTTP ' + xhr.status + ' (' + url + ')'));
        }
      };
      xhr.onerror = function() { reject(new Error('fetch failed: ' + url)); };
      xhr.send();
    });
  }-*/;

  private static native Promise<String> fetchUrlAsText(String url) /*-{
    return new Promise(function(resolve, reject) {
      var xhr = new XMLHttpRequest();
      var resolved = new URL(url, $wnd.top.location.href).href;
      xhr.open('GET', resolved, true);
      xhr.onload = function() {
        if (xhr.status === 200 || xhr.status === 0) {
          resolve(xhr.responseText || '');
        } else {
          reject(new Error('fetch failed: HTTP ' + xhr.status + ' (' + url + ')'));
        }
      };
      xhr.onerror = function() { reject(new Error('fetch failed: ' + url)); };
      xhr.send();
    });
  }-*/;

  private static native String describeError(Object error) /*-{
    if (error === null || error === undefined) return 'unknown error';
    if (typeof error === 'string') return error;
    if (error.message) return String(error.message);
    if (error.original && error.original.message) return String(error.original.message);
    try { return String(error); } catch (e) { return 'unstringifiable error'; }
  }-*/;

  private static native String basenameFromUrl(String url) /*-{
    var u = String(url), q = u.indexOf('?'), h = q >= 0 ? u.substring(0, q) : u;
    var s = h.lastIndexOf('/');
    return s >= 0 ? h.substring(s + 1) : h;
  }-*/;
}
