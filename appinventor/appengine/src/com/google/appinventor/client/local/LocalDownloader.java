// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2025 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.local;

import com.google.appinventor.client.ErrorReporter;
import com.google.appinventor.client.Ode;
import com.google.appinventor.client.utils.Downloader;
import com.google.appinventor.client.utils.Promise;
import com.google.appinventor.shared.rpc.ServerLayout;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.gwt.typedarrays.shared.ArrayBuffer;

import java.util.ArrayList;
import java.util.List;

public class LocalDownloader extends Downloader {
  @Override
  public final void download(String path) {
    ErrorReporter.hide();
    if (path == null) {
      reportFailure("Unable to download: path is null");
      return;
    }

    String[] parts = path.split("/");
    if (parts.length < 2) {
      reportFailure("Unable to download: invalid path " + path);
      return;
    }

    LocalProjectService projectService = (LocalProjectService) Ode.getInstance().getProjectService();
    String kind = parts[1];

    if (ServerLayout.DOWNLOAD_PROJECT_SOURCE.equals(kind)) {
      if (parts.length < 3) {
        reportFailure("Unable to export project: missing project id");
        return;
      }
      final String projectId = parts[parts.length - 1];
      final long id;
      try {
        id = Long.parseLong(projectId);
      } catch (NumberFormatException e) {
        reportFailure("Unable to export project: invalid project id " + projectId);
        return;
      }
      final String projectName = projectService.getProjectName(projectId) != null
          ? projectService.getProjectName(projectId) : "Project" + projectId;
      projectService.exportProject(id)
          .then(zip -> {
            triggerDownload(zip, projectName + ".aia");
            return Promise.resolve(zip);
          })
          .error(error -> {
            reportFailure("Unable to export project " + projectId + ": " + describe(error));
            return null;
          });
    } else if (ServerLayout.DOWNLOAD_SELECTED_PROJECTS_SOURCE.equals(kind)) {
      if (parts.length < 3) {
        reportFailure("Unable to export selected projects: no projects were selected");
        return;
      }
      List<Long> ids = parseProjectIds(parts[parts.length - 1]);
      if (ids == null || ids.isEmpty()) {
        reportFailure("Unable to export selected projects: invalid project ids");
        return;
      }
      projectService.exportProjects(ids)
          .then(zip -> {
            triggerDownload(zip, "selected-projects.aia");
            return Promise.resolve(zip);
          })
          .error(error -> {
            reportFailure("Unable to export selected projects: " + describe(error));
            return null;
          });
    } else if (ServerLayout.DOWNLOAD_ALL_PROJECTS_SOURCE.equals(kind)) {
      projectService.exportAllProjects()
          .then(zip -> {
            triggerDownload(zip, "all-projects.aia");
            return Promise.resolve(zip);
          })
          .error(error -> {
            reportFailure("Unable to export all projects: " + describe(error));
            return null;
          });
    } else if (ServerLayout.DOWNLOAD_USERFILE.equals(kind)) {
      if (parts.length < 3) {
        reportFailure("Unable to download user file: missing file name");
        return;
      }
      String fileName = parts[parts.length - 1];
      LocalIdbStore.getUserFile(fileName)
          .then(buffer -> {
            if (buffer == null) {
              reportFailure("Unable to download user file: " + fileName + " is not present");
            } else {
              triggerUserFileDownload(buffer, fileName);
            }
            return Promise.resolve(buffer);
          })
          .error(error -> {
            reportFailure("Unable to download user file " + fileName + ": " + describe(error));
            return null;
          });
    } else if (ServerLayout.DOWNLOAD_FILE.equals(kind)) {
      downloadProjectFile(parts, projectService);
    } else {
      reportFailure("Unsupported offline download path: " + path);
    }
  }

  private static List<Long> parseProjectIds(String encoded) {
    // ExportProjectAction encodes each id as <decimal-id>-, so a negative id
    // is represented as -123-. Splitting on '-' loses that sign; parse the
    // delimiter explicitly instead.
    List<Long> ids = new ArrayList<>();
    int index = 0;
    while (index < encoded.length()) {
      boolean negative = encoded.charAt(index) == '-';
      if (negative) {
        index++;
      }
      int digitsStart = index;
      while (index < encoded.length() && Character.isDigit(encoded.charAt(index))) {
        index++;
      }
      if (digitsStart == index) {
        return null;
      }
      try {
        long value = Long.parseLong(encoded.substring(digitsStart, index));
        ids.add(negative ? -value : value);
      } catch (NumberFormatException e) {
        return null;
      }
      if (index == encoded.length()) {
        break;
      }
      if (encoded.charAt(index) != '-') {
        return null;
      }
      index++;
    }
    return ids;
  }

  private static void downloadProjectFile(String[] parts, LocalProjectService projectService) {
    if (parts.length < 4) {
      reportFailure("Unable to download project file: invalid path");
      return;
    }
    final long projectId;
    try {
      projectId = Long.parseLong(parts[2]);
    } catch (NumberFormatException e) {
      reportFailure("Unable to download project file: invalid project id " + parts[2]);
      return;
    }
    StringBuilder fileBuilder = new StringBuilder();
    for (int i = 3; i < parts.length; i++) {
      if (i > 3) {
        fileBuilder.append('/');
      }
      fileBuilder.append(parts[i]);
    }
    String file = fileBuilder.toString();
    int query = file.indexOf('?');
    if (query >= 0) {
      file = file.substring(0, query);
    }
    final String requestedFile = file;
    String fileName = StorageUtil.basename(requestedFile);
    projectService.getFileDataUrl(projectId, requestedFile)
        .then(dataUrl -> {
          if (dataUrl == null || dataUrl.isEmpty()) {
            reportFailure("Unable to download project file: " + requestedFile + " is missing");
          } else {
            triggerFileDownload(dataUrl, fileName);
          }
          return Promise.resolve(dataUrl);
        })
        .error(error -> {
          reportFailure("Unable to download project file " + requestedFile + ": " + describe(error));
          return null;
        });
  }

  private static void reportFailure(String message) {
    ErrorReporter.reportError(message);
  }

  private static String describe(Object error) {
    return error == null ? "unknown error" : String.valueOf(error);
  }

  private static native void triggerDownload(String zipBase64, String fileName) /*-{
    try {
      var t = $wnd.__TAURI__;
      if (t && t.dialog && t.fs) {
        var opts = {};
        opts['defaultPath'] = fileName;
        var filters = [];
        var entry = {};
        entry['name'] = 'App Inventor Project';
        entry['extensions'] = ['aia'];
        filters[0] = entry;
        opts['filters'] = filters;
        t.dialog.save(opts).then(function(path) {
          if (!path) return;
          var bin = $wnd.atob(zipBase64);
          var buf = new ArrayBuffer(bin.length);
          var view = new Uint8Array(buf);
          for (var i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i) & 0xff;
          return t.fs.writeFile(path, buf);
        }, function(err) {
          $wnd.console.error('AI2 Tauri save failed: ' + err);
        }).then(function() {}, function(err) {
          $wnd.console.error('AI2 Tauri write failed: ' + err);
        });
        return;
      }
      var a = $doc.createElement('a');
      a.href = 'data:application/zip;base64,' + zipBase64;
      a.download = fileName;
      $doc.body.appendChild(a);
      a.click();
      $doc.body.removeChild(a);
    } catch (err) {
      $wnd.console.error('AI2 project download failed: ' + err);
    }
  }-*/;

  private static native void triggerUserFileDownload(ArrayBuffer buffer, String fileName) /*-{
    try {
      var t = $wnd.__TAURI__;
      if (t && t.dialog && t.fs) {
        var opts = {};
        opts['defaultPath'] = fileName;
        var filters = [];
        var entry = {};
        entry['name'] = 'Android keystore';
        entry['extensions'] = ['keystore'];
        filters[0] = entry;
        opts['filters'] = filters;
        t.dialog.save(opts).then(function(path) {
          if (!path) return;
          return t.fs.writeFile(path, new Uint8Array(buffer));
        }, function(err) {
          $wnd.console.error('AI2 Tauri keystore save failed: ' + err);
        }).then(function() {}, function(err) {
          $wnd.console.error('AI2 Tauri keystore write failed: ' + err);
        });
        return;
      }
      var bytes = new Uint8Array(buffer);
      var blob = new Blob([bytes], {type: 'application/octet-stream'});
      var url = URL.createObjectURL(blob);
      var a = $doc.createElement('a');
      a.href = url;
      a.download = fileName;
      $doc.body.appendChild(a);
      a.click();
      $doc.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (err) {
      $wnd.console.error('AI2 user file download failed: ' + err);
    }
  }-*/;

  private static native void triggerFileDownload(String dataUrl, String fileName) /*-{
    try {
      var t = $wnd.__TAURI__;
      var m = /^data:([^;,]+)?(;base64)?,(.*)$/.exec(dataUrl);
      var mime = (m && m[1]) ? m[1] : 'application/octet-stream';
      var isBase64 = !!(m && m[2]);
      var payload = (m && m[3]) ? m[3] : '';
      if (t && t.dialog && t.fs) {
        var opts = {};
        opts['defaultPath'] = fileName;
        var filters = [];
        var entry = {};
        entry['name'] = mime;
        entry['extensions'] = [fileName.substring(fileName.lastIndexOf('.') + 1)];
        filters[0] = entry;
        opts['filters'] = filters;
        t.dialog.save(opts).then(function(path) {
          if (!path) return;
          var bytes;
          if (isBase64) {
            var bin = $wnd.atob(payload);
            bytes = new ArrayBuffer(bin.length);
            var view = new Uint8Array(bytes);
            for (var i = 0; i < bin.length; i++) view[i] = bin.charCodeAt(i) & 0xff;
          } else {
            bytes = new TextEncoder().encode(decodeURIComponent(payload));
          }
          return t.fs.writeFile(path, bytes);
        }, function(err) {
          $wnd.console.error('AI2 Tauri file save failed: ' + err);
        }).then(function() {}, function(err) {
          $wnd.console.error('AI2 Tauri file write failed: ' + err);
        });
        return;
      }
      var a = $doc.createElement('a');
      a.href = dataUrl;
      a.download = fileName;
      $doc.body.appendChild(a);
      a.click();
      $doc.body.removeChild(a);
    } catch (err) {
      $wnd.console.error('AI2 file download failed: ' + err);
    }
  }-*/;
}
