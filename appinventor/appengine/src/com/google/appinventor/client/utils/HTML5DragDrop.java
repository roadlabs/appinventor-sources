// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2017-2020 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.utils;

import static com.google.appinventor.client.Ode.MESSAGES;

import com.google.appinventor.client.ErrorReporter;
import com.google.appinventor.client.Ode;
import com.google.appinventor.client.OdeAsyncCallback;
import com.google.appinventor.client.boxes.AssetListBox;
import com.google.appinventor.client.boxes.PaletteBox;
import com.google.appinventor.client.boxes.ProjectListBox;
import com.google.appinventor.client.editor.youngandroid.YaBlocksEditor;
import com.google.appinventor.client.explorer.dialogs.NoProjectDialogBox;
import com.google.appinventor.client.explorer.project.Project;
import com.google.appinventor.client.local.LocalIdbStore;
import com.google.appinventor.client.local.LocalProjectService;
import com.google.appinventor.client.utils.Promise;
import com.google.appinventor.client.wizards.ComponentImportWizard.ImportComponentCallback;
import com.google.appinventor.client.wizards.RequestNewProjectNameWizard;
import com.google.appinventor.client.wizards.RequestProjectNewNameInterface;
import com.google.appinventor.client.youngandroid.TextValidators;

import com.google.appinventor.shared.rpc.UploadResponse;
import com.google.appinventor.shared.rpc.project.UserProject;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidProjectNode;
import com.google.appinventor.shared.storage.StorageUtil;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.typedarrays.shared.ArrayBuffer;

import com.google.gwt.core.client.GWT;

import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;

import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;

import com.google.gwt.query.client.builders.JsniBundle;

import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.DialogBox;
import com.google.gwt.user.client.ui.DockPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.VerticalPanel;

import jsinterop.annotations.JsFunction;

/**
 * HTML5DragDrop implements support for dragging projects/extensions/assets from the developer's
 * computer into the browser and dropping them onto the workspace. Depending on the extension of
 * the file, one of uploadProject(), uploadExtension(), or uploadMedia() is called to trigger an
 * import of the dropped entity.
 *
 * Compatibility
 * -------------
 *
 * According to Mozilla, HTML5 Drag and Drop support is available starting in the following
 * browser versions:
 *
 *     Chrome: 4
 *     Edge: (always)
 *     Firefox: 3.5
 *     IE: 10
 *     Opera: 12
 *     Safari: 3.1
 */
@SuppressWarnings("checkstyle:JavadocParagraph")
public final class HTML5DragDrop {
  interface HTML5DragDropSupport extends JsniBundle {
    @LibrarySource("html5dnd.js")
    void init();
  }

  @JsFunction
  public interface ConfirmCallback {
    void run();
  }
  
  @JsFunction
  public interface StringCallback {
    void run(String name);
  }

  public static void init() {
    ((HTML5DragDropSupport) GWT.create(HTML5DragDropSupport.class)).init();
    initJsni();
  }


  private static native void initJsni()/*-{
    top.HTML5DragDrop_isProjectEditorOpen =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::isProjectEditorOpen());
    top.HTML5DragDrop_getOpenProjectId =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::getOpenProjectId());
    top.HTML5DragDrop_handleUploadResponse =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::handleUploadResponse(*));
    top.HTML5DragDrop_reportError =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::reportError(*));
    top.HTML5DragDrop_confirmOverwriteKey =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::confirmOverwriteKey(*));
    top.HTML5DragDrop_getNewProjectName =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::getNewProjectName(*));
    top.HTML5DragDrop_confirmOverwriteAsset =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::confirmOverwriteAsset(*));
    top.HTML5DragDrop_isBlocksEditorOpen =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::isBlocksEditorOpen());
    top.HTML5DragDrop_checkProjectNameForCollision =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::checkProjectNameForCollision(*));
    top.HTML5DragDrop_shouldShowDropTarget =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::shouldShowDropTarget(*));
    top.HTML5DragDrop_isLocalMode =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::isLocalMode());
    top.HTML5DragDrop_uploadAssetLocal =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::uploadAssetLocal(*));
    top.HTML5DragDrop_uploadExtensionLocal =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::uploadExtensionLocal(*));
    top.HTML5DragDrop_uploadKeystoreLocal =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::uploadKeystoreLocal(*));
    top.HTML5DragDrop_importProjectLocal =
      $entry(@com.google.appinventor.client.utils.HTML5DragDrop::importProjectLocal(*));
  }-*/;


  public static native void importProjectFromUrl(String url)  /*-{
    $wnd.HTML5DragDrop_importProject(url);
  }-*/;

  public static boolean isProjectEditorOpen() {
    return Ode.getInstance().getCurrentView() == 0;
  }

  public static boolean isBlocksEditorOpen() {
    return isProjectEditorOpen()
        && Ode.getInstance().getCurrentFileEditor() instanceof YaBlocksEditor;
  }

  public static String getOpenProjectId() {
    return Long.toString(Ode.getInstance().getCurrentYoungAndroidProjectId());
  }

  protected static void reportError(int errorCode) {
    switch (errorCode) {
      case 1:
        Window.alert("No project open to receive upload.");
        break;
      case 2:
        Window.alert("Uploading of APK files is not supported.");
        break;
      default:
        Window.alert("Unexpected HTTP error code: " + errorCode);
    }
  }

  protected static void confirmOverwriteKey(final ConfirmCallback callback) {
    Ode.getInstance().getUserInfoService().hasUserFile(StorageUtil.ANDROID_KEYSTORE_FILENAME,
        new OdeAsyncCallback<Boolean>(MESSAGES.uploadKeystoreError()) {
          @Override
          public void onSuccess(Boolean keystoreFileExists) {
            if (keystoreFileExists) {
              final DialogBox dialog = new DialogBox(false, true);
              dialog.setStylePrimaryName("ode-DialogBox");
              dialog.setText("Confirm Overwrite...");
              Button cancelButton = new Button(MESSAGES.cancelButton());
              Button deleteButton = new Button(MESSAGES.overwriteButton());
              DockPanel buttonPanel = new DockPanel();
              buttonPanel.add(cancelButton, DockPanel.WEST);
              buttonPanel.add(deleteButton, DockPanel.EAST);
              VerticalPanel panel = new VerticalPanel();
              Label label = new Label();
              label.setText(MESSAGES.confirmOverwriteKeystore());
              panel.add(label);
              panel.add(buttonPanel);
              dialog.add(panel);
              cancelButton.addClickHandler(new ClickHandler() {
                @Override
                public void onClick(ClickEvent event) {
                  dialog.hide();
                }
              });
              deleteButton.addClickHandler(new ClickHandler() {
                @Override
                public void onClick(ClickEvent event) {
                  dialog.hide();
                  callback.run();
                }
              });
              dialog.center();
              dialog.show();
            } else {
              callback.run();
            }
          }
        });
  }

  protected static void confirmOverwriteAsset(String _projectId, String name, final ConfirmCallback callback) {
    // Get the target project
    long projectId = Long.parseLong(_projectId);
    Project project = Ode.getInstance().getProjectManager().getProject(projectId);
    if (project == null) {
      // Project not open so we have nothing to do.
      return;
    }

    // Check if an asset already exists with the given name
    YoungAndroidProjectNode projectNode = (YoungAndroidProjectNode) project.getRootNode();
    YoungAndroidAssetNode node = (YoungAndroidAssetNode) projectNode.getAssetsFolder().findNode("assets/" + name);
    if (node == null) {
      // No asset exists by that name so it is safe to upload.
      callback.run();
      return;
    }

    // Ask user to confirm overwriting the asset
    // This currently uses the same mechanism as FileUploadWizard, but should be rewritten to use a
    // dialog at some point.
    if (Window.confirm(MESSAGES.confirmOverwrite(name, name))) {
      callback.run();
    }
  }

  /**
   * Checks the project name using the standard set of project name validators. If the project name
   * isn't valid, the drop will be aborted. It doesn't show an alert on invalid project name.
   *
   * @param projectName the project name based on the dropped file's name
   * @return true if the project name is allowed, otherwise false
   */
  protected static boolean checkProjectNameForCollision(String projectName) {
    return TextValidators.checkNewProjectName(projectName, true) 
            == TextValidators.ProjectNameStatus.SUCCESS;
  }
  
  /**
   * Shows dialog box to enter new project name when user tries
   * to upload a project with invalid Name.
   * 
   * @param filename initial filename of project , used to suggest a new name
   * @param callback callback to upload after user enters a valid name
   */
  protected static void getNewProjectName(String filename, final StringCallback callback) {  
    filename = filename.substring(0, filename.length() - 4);

    new RequestNewProjectNameWizard(new RequestProjectNewNameInterface() {
        @Override
        public void getNewName(String name) {
          callback.run(name);
        }
    }, filename, true);
  }

  protected static void handleUploadResponse(String projectIdStr, String type, String name,
      String body) {
    Ode ode = Ode.getInstance();
    UploadResponse response = UploadResponse.extractUploadResponse(body);
    if (response != null) {
      switch (response.getStatus()) {
        case SUCCESS:
          ErrorReporter.hide();
          if ("project".equals(type)) {
            String info = response.getInfo();
            UserProject userProject = UserProject.valueOf(info);
            Project uploadedProject = ode.getProjectManager().addProject(userProject);
            ode.openYoungAndroidProjectInDesigner(uploadedProject);
            NoProjectDialogBox.closeIfOpen();
          } else if ("extension".equals(type)) {
            long projectId = Long.parseLong(projectIdStr);
            YoungAndroidProjectNode projectNode = (YoungAndroidProjectNode) ode.getProjectManager()
                .getProject(projectId).getRootNode();
            ode.getComponentService().importComponentToProject(response.getInfo(), projectId,
                projectNode.getAssetsFolder().getFileId(), new ImportComponentCallback());
          } else if ("asset".equals(type)) {
            addAssetToProject(projectIdStr, name, response.getModificationDate());
          } else if ("keystore".equals(type)) {
            Ode.getInstance().getTopToolbar().updateKeystoreFileMenuButtons();
          }
          break;
        case FILE_TOO_LARGE:
          ErrorReporter.reportInfo(MESSAGES.fileTooLargeError());
          break;
        case NOT_PROJECT_ARCHIVE:
          ErrorReporter.reportInfo(MESSAGES.notProjectArchiveError());
          break;
        default:
          ErrorReporter.reportError(MESSAGES.fileUploadError());
      }
    } else {
      ErrorReporter.reportError(MESSAGES.fileUploadError());
    }
  }

  private static void addAssetToProject(String projectIdStr, String name, long modificationDate) {
    Ode ode = Ode.getInstance();
    long projectId = Long.parseLong(projectIdStr);
    ode.updateModificationDate(projectId, modificationDate);
    Project project = ode.getProjectManager().getProject(projectId);
    YoungAndroidProjectNode projectNode = (YoungAndroidProjectNode) project.getRootNode();
    YoungAndroidAssetNode node = new YoungAndroidAssetNode(name,
        projectNode.getAssetsFolder().getFileId() + "/" + name);
    project.addNode(projectNode.getAssetsFolder(), node);
  }

  /**
   * Returns whether the project service is the in-browser
   * {@link LocalProjectService}. Used by the JS drag-drop layer to decide
   * between the XHR upload path and the direct in-memory upload hook.
   */
  public static boolean isLocalMode() {
    return Ode.getInstance().getProjectService() instanceof LocalProjectService;
  }

  /**
   * Receives a dropped file from the JS drag-drop layer in offline mode. Reads
   * the blob bytes via FileReader, persists them through {@link LocalProjectService}, and adds
   * the asset node to the project tree (mirroring the online success path).
   *
   * @param projectIdStr the project id as a string (from
   *     {@code top.HTML5DragDrop_getOpenProjectId})
   * @param blob the dropped {@code Blob} (a {@code File} is also a {@code Blob})
   */
  public static void uploadAssetLocal(String projectIdStr, JavaScriptObject blob) {
    final long projectId;
    try {
      projectId = Long.parseLong(projectIdStr);
    } catch (NumberFormatException e) {
      reportError(1);
      return;
    }
    String name = blobName(blob);
    if (name == null || name.isEmpty()) {
      reportError(1);
      return;
    }
    final String fileId = "assets/" + name;
    LocalProjectService svc = (LocalProjectService) Ode.getInstance().getProjectService();
    readBlob(blob).then(buffer -> svc.saveContent(projectId, fileId, buffer))
        .then(date -> {
          addAssetToProject(projectIdStr, name, date);
          return Promise.resolve(null);
        })
        .error(err -> {
          ErrorReporter.reportError(MESSAGES.fileUploadError());
          return null;
        });
  }

  /**
   * Receives a dropped {@code .aix} extension from the JS drag-drop layer in
   * offline mode. Reads the blob bytes via FileReader, base64-encodes them
   * (for JSZip's base64 input mode), then delegates to
   * {@link com.google.appinventor.client.local.LocalComponentService#importComponentToProject}.
   * On success, replicates the {@code ImportComponentCallback.onSuccess} flow
   * (add node to components folder, call {@code projectEditor.importExtension})
   * to register the extension in the palette.
   *
   * @param projectIdStr the project id as a string (from
   *     {@code top.HTML5DragDrop_getOpenProjectId})
   * @param blob the dropped {@code Blob} (a {@code File} is also a {@code Blob})
   */
  public static void uploadExtensionLocal(String projectIdStr, JavaScriptObject blob) {
    final long projectId;
    try {
      projectId = Long.parseLong(projectIdStr);
    } catch (NumberFormatException e) {
      reportError(1);
      return;
    }
    final Project project = Ode.getInstance().getProjectManager().getProject(projectId);
    if (project == null) {
      reportError(1);
      return;
    }
    final YoungAndroidProjectNode projectNode =
        (YoungAndroidProjectNode) project.getRootNode();
    final com.google.appinventor.client.editor.youngandroid.YaProjectEditor projectEditor =
        (com.google.appinventor.client.editor.youngandroid.YaProjectEditor)
            Ode.getInstance().getEditorManager().getOpenProjectEditor(projectId);
    if (projectEditor == null) {
      reportError(1);
      return;
    }
    readBlobBase64(blob).then(base64 -> {
      com.google.appinventor.client.local.LocalComponentService svc =
          (com.google.appinventor.client.local.LocalComponentService)
              Ode.getInstance().getComponentService();
      svc.importComponentToProject(base64, projectId,
          projectNode.getAssetsFolder().getFileId(),
          new ImportComponentCallback());
      return Promise.resolve(null);
    }).error(err -> {
      ErrorReporter.reportError(MESSAGES.fileUploadError());
      return null;
    });
  }

  /** Stores a dropped Android keystore in the local user-file store. */
  public static void uploadKeystoreLocal(JavaScriptObject blob) {
    readBlob(blob)
        .then(buffer -> LocalIdbStore.putUserFile(StorageUtil.ANDROID_KEYSTORE_FILENAME, buffer))
        .then(value -> {
          Ode.getInstance().getTopToolbar().updateKeystoreFileMenuButtons();
          return Promise.resolve(null);
        })
        .error(err -> {
          ErrorReporter.reportError(MESSAGES.uploadKeystoreError());
          return null;
        });
  }

  /**
   * Imports an .aia downloaded from a repository in offline mode. The normal
   * path posts the file to UploadServlet, which is unavailable in the static
   * offline webapp.
   */
  public static void importProjectLocal(String projectName, JavaScriptObject blob) {
    if (projectName == null || projectName.isEmpty()) {
      ErrorReporter.reportError(MESSAGES.projectUploadError());
      return;
    }
    readBlobBase64(blob)
        .then(base64 -> {
          LocalProjectService svc = (LocalProjectService) Ode.getInstance().getProjectService();
          svc.newProjectFromExternalTemplate(projectName, base64,
              new AsyncCallback<UserProject>() {
                @Override
                public void onSuccess(UserProject project) {
                  if (project == null) {
                    ErrorReporter.reportError(MESSAGES.projectUploadError());
                    return;
                  }
                  Project imported = Ode.getInstance().getProjectManager().addProject(project);
                  Ode.getInstance().openYoungAndroidProjectInDesigner(imported);
                }

                @Override
                public void onFailure(Throwable caught) {
                  ErrorReporter.reportError(MESSAGES.projectUploadError());
                }
              });
          return Promise.resolve(null);
        })
        .error(err -> {
          ErrorReporter.reportError(MESSAGES.projectUploadError());
          return null;
        });
  }

  /** Reads a Blob and resolves with its bytes encoded as a base64 String. */
  private static native Promise<String> readBlobBase64(JavaScriptObject blob) /*-{
    return new Promise(function(resolve, reject) {
      var reader = new FileReader();
      reader.onload  = function(ev) {
        // ev.target.result is "data:<mime>;base64,<payload>" — strip prefix.
        var s = String(ev.target.result || '');
        var idx = s.indexOf('base64,');
        resolve(idx >= 0 ? s.substring(idx + 'base64,'.length) : s);
      };
      reader.onerror = function(ev) { reject(new Error('Failed to read dropped file')); };
      reader.readAsDataURL(blob);
    });
  }-*/;

  private static native String blobName(JavaScriptObject blob) /*-{
    try { return blob && blob.name ? String(blob.name) : ''; }
    catch (e) { return ''; }
  }-*/;

  private static native Promise<ArrayBuffer> readBlob(JavaScriptObject blob) /*-{
    return new Promise(function(resolve, reject) {
      var reader = new FileReader();
      reader.onload  = function(ev) { resolve(ev.target.result); };
      reader.onerror = function(ev) { reject(new Error('Failed to read dropped file')); };
      reader.readAsArrayBuffer(blob);
    });
  }-*/;

  /**
   * Determines whether the given element or an ancestor constitutes a drop target. If so, it will
   * return the Element that should be used for the bounds of the drop rectangle.
   *
   * NB: For security reasons, the browser does not share any information about the thing to be
   * dropped until it is actually dropped. This means we can't selectively show a drop target based
   * on what is being dragged. Ideally, we would only show the drop target for the project list if
   * the dragged item were an AIA and the drop target for the palette if the dragged item were an
   * AIX.
   *
   * @param target The source element for the drag/drop event
   * @return the element to use if the drop is valid, otherwise null
   */
  protected static Element shouldShowDropTarget(Element target) {
    if (Ode.getInstance().getCurrentView() == Ode.PROJECTS) {
      boolean noProjects = 0 == ProjectListBox.getProjectListBox()
          .getProjectList().getMyProjectsCount();
      while (target != Document.get().getBody()) {
        if (noProjects && target == Ode.getInstance().getOverDeckPanel().getElement()) {
          // If there aren't any projects, then we want to support dropping into the empty space
          return Ode.getInstance().getOverDeckPanel().getElement();
        } else if (target == ProjectListBox.getProjectListBox().getElement()) {
          // Allow dropping into the project list
          return target;
        }
        target = target.getParentElement();
      }
    } else if (Ode.getInstance().getCurrentView() == Ode.DESIGNER) {
      while (target != Document.get().getBody()) {
        if (target == AssetListBox.getAssetListBox().getElement()) {
          return target;  // Media list is a drop target
        } else if (target == PaletteBox.getPaletteBox().getElement()) {
          return target;  // Palette panel is a drop target (for extensions)
        }
        target = target.getParentElement();
      }
    }
    return null;  // No valid drop target
  }
}
