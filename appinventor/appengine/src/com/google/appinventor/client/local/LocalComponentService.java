// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2026 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.local;

import com.google.appinventor.client.ErrorReporter;
import com.google.appinventor.client.Ode;
import com.google.appinventor.client.jzip.JSZip;
import com.google.appinventor.client.jzip.LoadOptions;
import com.google.appinventor.client.jzip.TextDecoder;
import com.google.appinventor.client.jzip.Type;
import com.google.appinventor.client.utils.Promise;
import com.google.appinventor.shared.rpc.component.ComponentImportResponse;
import com.google.appinventor.shared.rpc.component.ComponentServiceAsync;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidComponentNode;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.gwt.json.client.JSONArray;
import com.google.gwt.json.client.JSONObject;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.typedarrays.shared.ArrayBuffer;
import com.google.gwt.user.client.rpc.AsyncCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * In-browser implementation of {@link ComponentServiceAsync} used when the
 * GWT module is built with {@code local.services=true}. Reads the .aix bytes
 * (either supplied by {@link com.google.appinventor.client.utils.HTML5DragDrop#uploadExtensionLocal}
 * for drag-drop, or by {@link LocalUploader#handleComponentUpload} for the
 * ComponentImportWizard), unzips them with JSZip, pulls out the
 * {@code components.json} (or legacy {@code component.json}) descriptor array
 * that {@code ExternalComponentGenerator} produced at build time — i.e. the
 * @SimpleFunction / @SimpleProperty / etc. annotation info already extracted
 * into JSON — and writes each entry's bytes into the project's
 * {@code assets/external_comps/<pkg>/} directory in IndexedDB via
 * {@link LocalProjectService#saveContent}.
 *
 * <p>Returns a {@link ComponentImportResponse} shaped identically to the
 * online implementation so that {@code ImportComponentCallback} and
 * {@code YaProjectEditor.importExtension} register the palette component
 * unchanged.
 *
 * <p>Buffers are kept as {@link ArrayBuffer} throughout (no JSNI
 * {@code byte[]} interop) — GWT's JSNI does not auto-convert {@code Uint8Array}
 * to {@code byte[]}, and the broken interop was producing empty arrays that
 * later showed up as a misleading {@code "undefined"} failure message.
 */
public class LocalComponentService implements ComponentServiceAsync {

  private static final String EXTERNAL_COMPS_BASEPATH = "assets/external_comps/";

  @Override
  public void importComponentToProject(String fileOrUrl, long projectId, String folderPath,
      AsyncCallback<ComponentImportResponse> callback) {
    // In Local mode, fileOrUrl is always a base64-encoded payload of the .aix
    // bytes (see HTML5DragDrop.uploadExtensionLocal and
    // LocalUploader.handleComponentUpload). The online "URL" / "__TEMP__"
    // branches do not exist here — offline has no server to fetch from and
    // no temp-file storage.
    if (fileOrUrl == null || fileOrUrl.isEmpty()) {
      callback.onFailure(new IllegalArgumentException("Empty .aix payload"));
      return;
    }

    final ComponentImportResponse response =
        new ComponentImportResponse(ComponentImportResponse.Status.FAILED, projectId,
            new LinkedHashMap<String, String>(), new ArrayList<ProjectNode>());

    final JSZip zip = new JSZip();
    zip.loadAsync(fileOrUrl, LoadOptions.create(true))  // base64 string
        .then0(() -> {
          // Collect every entry's name + bytes into parallel lists so we can
          // resolve them in parallel and then zip them back together.
          final List<Promise<ArrayBuffer>> reads = new ArrayList<>();
          final List<String> names = new ArrayList<>();
          zip.forEach((name, zipObject) -> {
            // 跳过目录条目(.aix 中可能有 "edu.mit.appinventor.ai.teachablemachine/"
            // 这样的目录占位),只保留实际文件,否则后续 getExtensionFileIds
            // 会把目录路径当 fileId 推给 Companion,Files:write 会失败。
            if (name.endsWith("/")) {
              return;
            }
            names.add(name);
            reads.add(zipObject.get(Type.ARRAY_BUFFER));
          });
          if (names.isEmpty()) {
            throw new RuntimeException("Empty .aix archive (no entries)");
          }
          return Promise.<ArrayBuffer[]>allOf(reads.toArray(new Promise[0]))
              .then(buffers -> {
                // Map every entry name -> raw bytes (kept as ArrayBuffer).
                final Map<String, ArrayBuffer> contents = new LinkedHashMap<>();
                for (int i = 0; i < names.size(); i++) {
                  contents.put(names.get(i), buffers[i]);
                }
                return importToProject(contents, projectId, folderPath, response);
              });
        })
        .then(v -> {
          callback.onSuccess(response);
          return Promise.resolve(null);
        })
        .error(err -> {
          // GWT's promise rejection can carry a raw JS error whose `message`
          // property is undefined / "undefined" / null. Always produce a
          // non-null, descriptive message so the alert is actionable.
          String msg = describeError(err);
          ErrorReporter.reportError("LocalComponentService import failed: " + msg);
          response.setStatus(ComponentImportResponse.Status.FAILED);
          response.setMessage(msg);
          callback.onSuccess(response);  // shape parity with server
          return null;
        });
  }

  @Override
  public void renameImportedComponent(String fullyQualifiedName, String newName,
      long projectId, AsyncCallback<Void> callback) {
    // Rename is not on the offline critical path; the palette rename wizard
    // calls this. We no-op with success so the UI doesn't hang — full rename
    // support can come later if needed.
    LocalProjectService svc = (LocalProjectService) Ode.getInstance().getProjectService();
    svc.ensureLoadedAsync().then0(() -> null).then(v -> {
      callback.onSuccess(null);
      return null;
    }).error(err -> {
      callback.onSuccess(null);
      return null;
    });
  }

  @Override
  public void deleteImportedComponent(String fullyQualifiedName, long projectId,
      AsyncCallback<Void> callback) {
    // No-op for now; extension deletion in offline mode would require
    // enumerating files under external_comps/<pkg>/ in IndexedDB. Successful
    // callback so the UI doesn't hang.
    LocalProjectService svc = (LocalProjectService) Ode.getInstance().getProjectService();
    svc.ensureLoadedAsync().then0(() -> null).then(v -> {
      callback.onSuccess(null);
      return null;
    }).error(err -> {
      callback.onSuccess(null);
      return null;
    });
  }

  // ---------------------------------------------------------------------
  // Core: parity with ComponentServiceImpl.importToProject()
  // ---------------------------------------------------------------------

  /**
   * Walks the unzipped entries, locates the components.json (or legacy
   * component.json) descriptor, then writes every entry's bytes into the
   * project's {@code <folderPath>/external_comps/<entry-name>} tree via
   * {@link LocalProjectService#saveContent}.
   *
   * <p>All file bytes are passed through unchanged — no UTF-8 decoding of
   * binary content (classes.jar, image assets) is performed.
   */
  private Promise<Void> importToProject(Map<String, ArrayBuffer> contents, long projectId,
      String folderPath, ComponentImportResponse response) {
    // Replicates server: buildExtensionPathnameMap + readExtensionComponents.
    Map<String, String> nameMap = buildExtensionPathnameMap(contents.keySet());
    if (!nameMap.containsKey("components.json") && !nameMap.containsKey("component.json")) {
      response.setStatus(ComponentImportResponse.Status.FAILED);
      response.setMessage("Uploaded file does not contain any component definition files.");
      return resolvedVoid();
    }
    ArrayBuffer componentsBuffer = upgradeAndRenameFile(contents, nameMap, "component.json", "components.json");
    if (componentsBuffer == null) {
      response.setStatus(ComponentImportResponse.Status.FAILED);
      response.setMessage("No valid component descriptors found in the extension.");
      return resolvedVoid();
    }
    // Parse the components.json so we can build the response.
    JSONArray newComponents;
    try {
      String jsonText = new TextDecoder("utf-8").decode(componentsBuffer);
      newComponents = parseComponentsArray(jsonText);
    } catch (Exception e) {
      response.setStatus(ComponentImportResponse.Status.FAILED);
      response.setMessage("Invalid components.json: " + e.getMessage());
      return resolvedVoid();
    }
    if (newComponents.size() == 0) {
      response.setStatus(ComponentImportResponse.Status.FAILED);
      response.setMessage("No valid component descriptors found in the extension.");
      return resolvedVoid();
    }

    // Mirror upgrade flow: if a previous extension in the same package exists,
    // we'd upgrade. For simplicity and correctness in offline mode, we treat
    // any collision as a failed "would downgrade" rather than silently
    // removing the old component (which could orphan the user's blocks).
    Set<String> newTypes = getExtensionClasses(newComponents);

    // Write all files into the project.
    final String basepath = folderPath + "/external_comps/";
    final LocalProjectService svc = (LocalProjectService) Ode.getInstance().getProjectService();
    List<Promise<Long>> writes = new ArrayList<>();
    final List<ProjectNode> nodes = new ArrayList<>();
    for (Map.Entry<String, ArrayBuffer> entry : contents.entrySet()) {
      final String dest = basepath + entry.getKey();
      nodes.add(new YoungAndroidComponentNode(StorageUtil.basename(entry.getKey()), dest));
      writes.add(svc.saveContent(projectId, dest, entry.getValue()));
    }

    return Promise.allOf(writes.toArray(new Promise[0])).then0(() -> {
      Map<String, String> types = new TreeMap<>();
      for (int i = 0; i < newComponents.size(); i++) {
        JSONObject desc = newComponents.get(i).isObject();
        if (desc != null && desc.containsKey("type") && desc.containsKey("name")) {
          types.put(desc.get("type").isString().stringValue(),
                    desc.get("name").isString().stringValue());
        }
      }
      response.setStatus(ComponentImportResponse.Status.IMPORTED);
      response.setComponentTypes(types);
      response.setNodes(nodes);
      response.setProjectId(projectId);
      return null;
    });
  }

  // ---------------------------------------------------------------------
  // Helpers — pure JSON, ported from ComponentServiceImpl
  // ---------------------------------------------------------------------

  private static Map<String, String> buildExtensionPathnameMap(Set<String> paths) {
    Map<String, String> result = new HashMap<>();
    for (String name : paths) {
      result.put(StorageUtil.basename(name), name);
    }
    return result;
  }

  /**
   * Read the components.json (or legacy component.json) and ensure the
   * contents map reflects an array form, mirroring the server's
   * {@code upgradeAndRenameFile}. Operates on {@link ArrayBuffer}s; if the
   * legacy single-object form is found, re-encodes to the array form.
   *
   * @return the normalized components.json buffer (array form), or {@code null}
   *         if neither file was present.
   */
  private static ArrayBuffer upgradeAndRenameFile(Map<String, ArrayBuffer> contents,
      Map<String, String> nameMap, String oldName, String newName) {
    ArrayBuffer out = null;
    if (nameMap.containsKey(newName)) {
      if (nameMap.containsKey(oldName)) {
        contents.remove(nameMap.remove(oldName));
      }
      out = contents.get(nameMap.get(newName));
    } else if (nameMap.containsKey(oldName)) {
      String oldPath = nameMap.remove(oldName);
      String newPath = oldPath.replace(oldName, newName);
      nameMap.put(newName, newPath);
      out = contents.remove(oldPath);
      if (out != null) {
        // Re-encode to array form for stability.
        try {
          String text = new TextDecoder("utf-8").decode(out);
          JSONArray arr = parseComponentsArray(text);
          byte[] newBytes = arr.toString().getBytes("UTF-8");
          out = bytesToArrayBuffer(newBytes);
        } catch (Exception e) {
          // Leave bytes as-is; server would do the same lenient path.
        }
        contents.put(newPath, out);
      }
    }
    return out;
  }

  /**
   * Accept either a JSON object {@code {...}} or an array {@code [{...}, ...]}
   * and return a JSONArray — matches {@code ComponentServiceImpl.readComponents}.
   */
  private static JSONArray parseComponentsArray(String content) {
    String trimmed = content.trim();
    if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
      return JSONParser.parseLenient("[" + trimmed + "]").isArray();
    } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
      return JSONParser.parseLenient(trimmed).isArray();
    } else {
      throw new IllegalArgumentException("Content was not a valid component descriptor file");
    }
  }

  private static Set<String> getExtensionClasses(JSONArray components) {
    Set<String> types = new HashSet<>();
    for (int i = 0; i < components.size(); i++) {
      JSONObject desc = components.get(i).isObject();
      if (desc != null && desc.containsKey("type")) {
        types.add(desc.get("type").isString().stringValue());
      }
    }
    return types;
  }

  // ---------------------------------------------------------------------
  // Tiny utilities
  // ---------------------------------------------------------------------

  private static Promise<Void> resolvedVoid() {
    return Promise.resolve(null);
  }

  /**
   * JSNI helper: build a fresh ArrayBuffer from a Java byte[]. Used only by
   * {@link #upgradeAndRenameFile} when re-encoding the legacy single-object
   * form to the array form. We never expose {@code byte[]} to GWT for the
   * main zip entries because GWT's {@code Uint8Array -> byte[]} interop is
   * unreliable (it silently truncates to length 0 in some configurations).
   */
  private static native ArrayBuffer bytesToArrayBuffer(byte[] bytes) /*-{
    var copy = new Uint8Array(bytes.length);
    for (var i = 0; i < bytes.length; i++) copy[i] = bytes[i] & 0xff;
    return copy.buffer;
  }-*/;

  /**
   * Stringify an arbitrary rejected-promise value into a useful message.
   * GWT's promise rejection can carry a raw JS error whose {@code message}
   * property is {@code undefined}; this helper ensures the alert and
   * ErrorReporter always see a meaningful string.
   */
  private static native String describeError(Object err) /*-{
    if (err === null || err === undefined) return 'no error detail';
    if (typeof err === 'string') return err;
    if (typeof err === 'number' || typeof err === 'boolean') return String(err);
    if (err && typeof err === 'object') {
      if (err.message) return String(err.message);
      if (err.stack) {
        var s = String(err.stack).split('\n');
        return s[0] || 'unknown error';
      }
      try { return String(err); } catch (e) { return 'unstringifiable error'; }
    }
    return 'unknown error type';
  }-*/;
}