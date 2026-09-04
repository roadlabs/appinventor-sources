// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2025 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.local;

import com.google.appinventor.client.Ode;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.appinventor.client.utils.Promise;
import com.google.appinventor.client.utils.Uploader;
import com.google.appinventor.shared.rpc.ServerLayout;
import com.google.appinventor.shared.rpc.UploadResponse;
import com.google.appinventor.shared.rpc.UploadResponse.Status;
import com.google.appinventor.shared.rpc.project.UserProject;
import com.google.gwt.dom.client.Element;
import com.google.gwt.typedarrays.shared.ArrayBuffer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.FileUpload;

import java.util.HashMap;
import java.util.Map;

public class LocalUploader extends Uploader {
  interface UploadHandler {
    void process(FileUpload upload, String[] urlParts, AsyncCallback<UploadResponse> callback);
  }

  private static final Map<String, UploadHandler> UPLOAD_HANDLERS = new HashMap<>();

  static {
    UPLOAD_HANDLERS.put(ServerLayout.UPLOAD_PROJECT, LocalUploader::handleProjectUpload);
    UPLOAD_HANDLERS.put(ServerLayout.UPLOAD_FILE, LocalUploader::handleFileUpload);
    UPLOAD_HANDLERS.put(ServerLayout.UPLOAD_COMPONENT, LocalUploader::handleComponentUpload);
    UPLOAD_HANDLERS.put(ServerLayout.UPLOAD_USERFILE, LocalUploader::handleUserFileUpload);
  }

  @Override
  public void upload(FileUpload upload, String uploadUrl, AsyncCallback<UploadResponse> callback) {
    String[] parts = uploadUrl.replace(ServerLayout.getModuleBaseURL(), "").split("/");
    if (parts.length < 3) {
      callback.onFailure(new IllegalArgumentException("Invalid upload URL: " + uploadUrl));
      return;
    }
    UploadHandler handler = UPLOAD_HANDLERS.get(parts[1]);
    if (handler != null) {
      handler.process(upload, parts, callback);
    } else {
      callback.onFailure(new UnsupportedOperationException("Unsupported upload url: " + uploadUrl));
    }
  }

  private static void handleProjectUpload(FileUpload upload, String[] urlParts,
      AsyncCallback<UploadResponse> callback) {
    String projectName = urlParts[2];
    getFileBase64(upload.getElement())
        .then(zipBase64 -> Promise.<UserProject>call("Project upload failed",
            c -> Ode.getInstance().getProjectService()
                .newProjectFromExternalTemplate(projectName, zipBase64, c)))
        .then(project -> {
          UploadResponse response = new UploadResponse(Status.SUCCESS, 0, project.toString());
          callback.onSuccess(response);
          return Promise.resolve(response);
        })
        .error(error -> {
          callback.onFailure(new Exception("Failed to upload file: " + error));
          return Promise.reject(error);
        });
  }

  private static void handleFileUpload(FileUpload upload, String[] urlParts,
      AsyncCallback<UploadResponse> callback) {
    // For a media upload, FileUploadWizard builds:
    //   <base>/upload/file/<projectId>/<folderFileId>/<filename>
    // where folderNode.getFileId() is e.g. "assets". The module base URL ends
    // in a slash, so after stripping it the split produces:
    //   parts[0]="upload" parts[1]="file" parts[2]=<projectId>
    //   parts[3]="assets" parts[4]=<filename>
    // Reconstruct the fileId from the trailing segments, skipping any empty
    // segment the URL may contain (defensive; none do today).
    if (urlParts.length < 5) {
      callback.onFailure(new IllegalArgumentException("Invalid file upload url"));
      return;
    }
    final long projectId;
    try {
      projectId = Long.parseLong(urlParts[2]);
    } catch (NumberFormatException e) {
      callback.onFailure(new IllegalArgumentException("Invalid projectId in upload url"));
      return;
    }
    StringBuilder fileId = new StringBuilder();
    for (int i = 3; i < urlParts.length; i++) {
      if (urlParts[i].isEmpty()) {
        continue;
      }
      if (fileId.length() > 0) {
        fileId.append('/');
      }
      fileId.append(urlParts[i]);
    }

    getFileBytes(upload.getElement())
        .then(buffer -> {
          LocalProjectService svc = (LocalProjectService) Ode.getInstance().getProjectService();
          return svc.saveContent(projectId, fileId.toString(), buffer);
        })
        .then(date -> {
          callback.onSuccess(new UploadResponse(Status.SUCCESS, date.longValue()));
          return Promise.resolve(null);
        })
        .error(err -> {
          callback.onFailure(new Exception("Failed to upload file: " + err));
          return null;
        });
  }

  private static native Promise<String> getFileBase64(Element element) /*-{
    function _arrayBufferToBase64(buffer) {
      var binary = '';
      var bytes = new Uint8Array(buffer);
      var len = bytes.byteLength;
      for (var i = 0; i < len; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      return $wnd.btoa(binary);
    }
    return new Promise(function(resolve, reject) {
      var reader = new FileReader();
      reader.onload = function(event) {
        resolve(_arrayBufferToBase64(event.target.result));
      };
      reader.onerror = function(event) {
        reject(new Error("Failed to read file: " + event.target.error));
      };
      reader.readAsArrayBuffer(element.files[0]);
    });
  }-*/;

  private static native Promise<ArrayBuffer> getFileBytes(Element element) /*-{
    return new Promise(function(resolve, reject) {
      var reader = new FileReader();
      reader.onload = function(event) { resolve(event.target.result); };
      reader.onerror = function(event) {
        reject(new Error("Failed to read file: " + event.target.error));
      };
      reader.readAsArrayBuffer(element.files[0]);
    });
  }-*/;

  /**
   * Handles the {@code /upload/component/<filename>} path used by
   * {@link com.google.appinventor.client.wizards.ComponentImportWizard} (and
   * the dead-code {@link com.google.appinventor.client.wizards.ComponentUploadWizard}).
   *
   * <p>Online: {@code UploadServlet} writes the .aix bytes to a temp file and
   * returns the temp path in {@link UploadResponse#getInfo()}, then
   * {@code ComponentServiceImpl.importComponentToProject} reopens the temp file.
   *
   * <p>Offline: we skip the temp-file round-trip — the FileReader already gives
   * us the bytes, so we encode them to base64 and pass that string straight
   * back via {@link UploadResponse#info}. The wizard then forwards
   * {@code info} to {@code ComponentService.importComponentToProject}, which in
   * offline mode is bound to {@link LocalComponentService} — that class accepts
   * a base64 String (see {@code LocalComponentService#importComponentToProject}).
   *
   * <p>The trailing {@code <filename>} segment is unused here; the actual
   * import into the project is wired up by the calling wizard once it has the
   * base64 string and the active project/folderPath.
   */
  private static void handleUserFileUpload(FileUpload upload, String[] urlParts,
      AsyncCallback<UploadResponse> callback) {
    if (urlParts.length < 3 || !StorageUtil.ANDROID_KEYSTORE_FILENAME.equals(urlParts[2])) {
      callback.onFailure(new IllegalArgumentException("Unsupported user file"));
      return;
    }
    getFileBytes(upload.getElement())
        .then(buffer -> LocalIdbStore.putUserFile(urlParts[2], buffer))
        .then(value -> {
          callback.onSuccess(new UploadResponse(Status.SUCCESS,
              System.currentTimeMillis()));
          return Promise.resolve(null);
        })
        .error(error -> {
          callback.onFailure(new Exception("Failed to store user file: " + error));
          return null;
        });
  }

  private static void handleComponentUpload(FileUpload upload, String[] urlParts,
      AsyncCallback<UploadResponse> callback) {
    if (urlParts.length < 3) {
      callback.onFailure(new IllegalArgumentException("Invalid component upload url"));
      return;
    }
    getFileBase64(upload.getElement())
        .then(base64 -> {
          callback.onSuccess(new UploadResponse(Status.SUCCESS, 0, base64));
          return Promise.resolve(null);
        })
        .error(err -> {
          callback.onFailure(new Exception("Failed to read .aix: " + err));
          return null;
        });
  }
}
