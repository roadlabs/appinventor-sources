// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2012 MIT, All rights reserved
// Released under the Apache License, Version 2.0
// http://www.apache.org/licenses/LICENSE-2.0

package com.google.appinventor.client.explorer.youngandroid;

import static com.google.appinventor.client.Ode.MESSAGES;

import com.google.appinventor.client.Images;
import com.google.appinventor.client.Ode;
import com.google.appinventor.client.explorer.project.Project;
import com.google.appinventor.client.explorer.project.ProjectChangeListener;
import com.google.appinventor.client.explorer.project.ProjectNodeContextMenu;
import com.google.appinventor.client.local.LocalProjectService;
import com.google.appinventor.client.utils.Promise;
import com.google.appinventor.client.widgets.TextButton;
import com.google.appinventor.client.wizards.FileUploadWizard;
import com.google.appinventor.shared.rpc.project.ProjectNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetNode;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidAssetsFolder;
import com.google.appinventor.shared.rpc.project.youngandroid.YoungAndroidProjectNode;
import com.google.appinventor.shared.storage.StorageUtil;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.MouseMoveEvent;
import com.google.gwt.event.dom.client.MouseMoveHandler;
import com.google.gwt.event.dom.client.BlurEvent;
import com.google.gwt.event.dom.client.BlurHandler;
import com.google.gwt.event.dom.client.FocusEvent;
import com.google.gwt.event.dom.client.FocusHandler;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.event.dom.client.MouseOutEvent;
import com.google.gwt.event.dom.client.MouseOutHandler;
import com.google.gwt.event.dom.client.MouseOverEvent;
import com.google.gwt.event.dom.client.MouseOverHandler;
import com.google.gwt.event.logical.shared.SelectionEvent;
import com.google.gwt.event.logical.shared.SelectionHandler;
import com.google.gwt.user.client.Event;
import com.google.gwt.user.client.ui.Composite;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.Image;
import com.google.gwt.user.client.ui.PopupPanel;
import com.google.gwt.user.client.ui.SimplePanel;
import com.google.gwt.user.client.ui.Tree;
import com.google.gwt.user.client.ui.TreeItem;
import com.google.gwt.user.client.ui.VerticalPanel;
import java.util.logging.Logger;

/**
 * The asset list shows all the project's assets, and lets the
 * user delete assets.
 *
 */

public class AssetList extends Composite implements ProjectChangeListener {
  private static final Logger LOG = Logger.getLogger(AssetList.class.getName());

  // The asset "list" is represented as a tree and follows the same GWT conventions.
  private Tree assetList;
  private final VerticalPanel panel;

  private long projectId;
  private Project project;
  private YoungAndroidAssetsFolder assetsFolder;
  private int clientX;
  private int clientY;

  // Hover preview state
  private PopupPanel previewPopup;
  private HTML previewContent;
  private String lastHoverKey;

  /**
   * Creates a new AssetList
   */
  public AssetList() {

    assetList = new Tree();
    assetList.setWidth("100%");

    panel = new VerticalPanel();
    panel.setWidth("100%");

    panel.add(assetList);

    TextButton addButton = new TextButton(MESSAGES.addButton());
    addButton.addClickHandler(new ClickHandler() {
      @Override
      public void onClick(ClickEvent event) {
        if (assetsFolder != null) {
          new FileUploadWizard(assetsFolder).show();
        }
      }
    });

    SimplePanel buttonPanel = new SimplePanel();
    buttonPanel.setStyleName("ode-PanelButtons");
    buttonPanel.add(addButton);

    panel.add(buttonPanel);
    panel.setCellHorizontalAlignment(buttonPanel, VerticalPanel.ALIGN_CENTER);

    initWidget(panel);

    assetList.setScrollOnSelectEnabled(false);
    assetList.sinkEvents(Event.ONMOUSEMOVE);
    assetList.addMouseMoveHandler(new MouseMoveHandler() {
      @Override
      public void onMouseMove(MouseMoveEvent event) {
        clientX = event.getClientX();
        clientY = event.getClientY();
      }
    });
    assetList.addSelectionHandler(new SelectionHandler<TreeItem>() {
      @Override
      public void onSelection(SelectionEvent<TreeItem> event) {
        TreeItem selected = event.getSelectedItem();
        ProjectNode node = (ProjectNode) selected.getUserObject();
        // The actual menu is determined by what is registered for the filenode
        // type in CommandRegistry.java
        ProjectNodeContextMenu.show(node, selected.getWidget(), clientX, clientY);
      }});
    assetList.addFocusHandler(new FocusHandler() {
      @Override
      public void onFocus(FocusEvent event) {
        assetList.addStyleName("gwt-Tree-focused");
      }
    });
    assetList.addBlurHandler(new BlurHandler() {
      @Override
      public void onBlur(BlurEvent event) {
        assetList.removeStyleName("gwt-Tree-focused");
      }
    });

    // Hover preview popup: shown when the mouse hovers an image/audio/video
    // asset row, hidden when the mouse leaves both the tree and the popup.
    previewPopup = new PopupPanel(true);
    previewPopup.setStyleName("ode-AssetPreview");
    previewContent = new HTML();
    previewPopup.add(previewContent);
    previewPopup.hide();

    assetList.addDomHandler(new MouseOverHandler() {
      @Override
      public void onMouseOver(MouseOverEvent event) {
        Element target = event.getNativeEvent().getEventTarget().<Element>cast();
        TreeItem hovered = findTreeItemForElement(target);
        if (hovered == null) {
          return;
        }
        Object uo = hovered.getUserObject();
        if (!(uo instanceof YoungAndroidAssetNode)) {
          return;
        }
        YoungAndroidAssetNode asset = (YoungAndroidAssetNode) uo;
        showPreview(asset.getProjectId(), asset.getFileId(),
            event.getClientX(), event.getClientY());
      }
    }, MouseOverEvent.getType());

    assetList.addDomHandler(new MouseOutHandler() {
      @Override
      public void onMouseOut(MouseOutEvent event) {
        Element related = event.getRelativeElement();
        if (related != null && assetList.getElement().isOrHasChild(related)) {
          return;
        }
        if (related != null && previewPopup.getElement().isOrHasChild(related)) {
          return;
        }
        hidePreview();
      }
    }, MouseOutEvent.getType());

    previewPopup.addDomHandler(new MouseOutHandler() {
      @Override
      public void onMouseOut(MouseOutEvent event) {
        Element related = event.getRelativeElement();
        if (related != null && previewPopup.getElement().isOrHasChild(related)) {
          return;
        }
        if (related != null && assetList.getElement().isOrHasChild(related)) {
          return;
        }
        hidePreview();
      }
    }, MouseOutEvent.getType());
  }

  /*
   * Populate the asset tree with files from the project's assets folder.
   */
  private void refreshAssetList() {
    final Images images = Ode.getImageBundle();
    LOG.info("AssetList: refreshing for project " + projectId);
    assetList.clear();

    if (assetsFolder != null) {
      for (ProjectNode node : assetsFolder.getChildren()) {
        // Add the name to the tree. We need to enclose it in a span
        // because the CSS style for selection specifies a span.
        String nodeName = node.getName();
        if (nodeName.length() > 20)
          nodeName = nodeName.substring(0, 8) + "..." + nodeName.substring(nodeName.length() - 9,
              nodeName.length());

        String fileSuffix = node.getProjectId() + "/" + node.getFileId();
        String treeItemText = "<span style='cursor: pointer'>";
        if (StorageUtil.isImageFile(fileSuffix)) {
          treeItemText += new Image(images.mediaIconImg());
        } else if (StorageUtil.isAudioFile(fileSuffix )) {
          treeItemText += new Image(images.mediaIconAudio());
        } else if (StorageUtil.isVideoFile(fileSuffix )) {
          treeItemText += new Image(images.mediaIconVideo());
        }
        treeItemText += nodeName + "</span>";
        TreeItem treeItem = new TreeItem(new HTML(treeItemText));
        // keep a pointer from the tree item back to the actual node
        treeItem.setUserObject(node);
        assetList.addItem(treeItem);
      }
    }
  }

  public void refreshAssetList(long projectId) {
    LOG.info("AssetList: switching projects from  " + this.projectId +
        " to " + projectId);

    if (project != null) {
      project.removeProjectChangeListener(this);
    }

    this.projectId = projectId;
    if (projectId != 0) {
      project = Ode.getInstance().getProjectManager().getProject(projectId);
      assetsFolder = ((YoungAndroidProjectNode) project.getRootNode()).getAssetsFolder();
      project.addProjectChangeListener(this);
    } else {
      project = null;
      assetsFolder = null;
    }

    refreshAssetList();
  }

  // ProjectChangeListener implementation
  @Override
  public void onProjectLoaded(Project project) {
    LOG.info("AssetList: got onProjectLoaded for " + project.getProjectId() +
        ", current project is " + projectId);
    refreshAssetList();
  }

  @Override
  public void onProjectNodeAdded(Project project, ProjectNode node) {
    LOG.info("AssetList: got projectNodeAdded for node " + node.getFileId()
        + " and project "  + project.getProjectId() + ", current project is " + projectId);
    if (node instanceof YoungAndroidAssetNode) {
      refreshAssetList();
    }
  }

  @Override
  public void onProjectNodeRemoved(Project project, ProjectNode node) {
    LOG.info("AssetList: got onProjectNodeRemoved for node " + node.getFileId()
        + " and project "  + project.getProjectId() + ", current project is " + projectId);
    if (node instanceof YoungAndroidAssetNode) {
      refreshAssetList();
    }
  }

  public Tree getTree() {
    return assetList;
  }

  // -------- Hover preview helpers --------

  private TreeItem findTreeItemForElement(Element start) {
    if (start == null) {
      return null;
    }
    int n = assetList.getItemCount();
    for (int i = 0; i < n; i++) {
      TreeItem item = assetList.getItem(i);
      TreeItem hit = matchItem(item, start);
      if (hit != null) {
        return hit;
      }
    }
    return null;
  }

  private static TreeItem matchItem(TreeItem item, Element start) {
    Element el = item.getElement();
    if (el != null && el.isOrHasChild(start)) {
      return item;
    }
    int n = item.getChildCount();
    for (int j = 0; j < n; j++) {
      TreeItem child = item.getChild(j);
      TreeItem hit = matchItem(child, start);
      if (hit != null) {
        return hit;
      }
    }
    return null;
  }

  private void showPreview(long projectId, String fileId, int clientX, int clientY) {
    String suffix = projectId + "/" + fileId;
    if (!(StorageUtil.isImageFile(suffix)
        || StorageUtil.isAudioFile(suffix)
        || StorageUtil.isVideoFile(suffix))) {
      return;
    }
    String key = projectId + ":" + fileId;
    if (key.equals(lastHoverKey) && previewPopup.isShowing()) {
      return;
    }
    lastHoverKey = key;

    Object projectService = Ode.getInstance().getProjectService();
    if (!(projectService instanceof LocalProjectService)) {
      // Online build: the right-click Preview command handles a full preview;
      // the hover popup does nothing.
      return;
    }
    ((LocalProjectService) projectService).getAssetDataUrl(projectId, fileId)
        .then(dataUrl -> {
          // If the user has moved on to another row, ignore this stale result.
          if (!key.equals(lastHoverKey)) {
            return Promise.resolve(null);
          }
          String body;
          if (dataUrl == null || dataUrl.isEmpty()) {
            body = "<div class='ode-AssetPreview-empty'>Preview unavailable</div>";
          } else if (StorageUtil.isImageFile(suffix)) {
            body = "<img src='" + dataUrl + "' style='max-width:200px;max-height:200px;'/>";
          } else if (StorageUtil.isAudioFile(suffix)) {
            body = "<audio controls src='" + dataUrl + "' style='width:240px;'></audio>";
          } else {
            body = "<video controls muted src='" + dataUrl
                + "' style='max-width:200px;max-height:200px;'></video>";
          }
          previewContent.setHTML(body);
          int x = clientX + 12;
          int y = clientY + 12;
          int maxX = com.google.gwt.user.client.Window.getClientWidth() - 230;
          int maxY = com.google.gwt.user.client.Window.getClientHeight() - 230;
          if (x > maxX) {
            x = Math.max(0, maxX);
          }
          if (y > maxY) {
            y = Math.max(0, maxY);
          }
          previewPopup.setPopupPosition(x, y);
          previewPopup.show();
          return Promise.resolve(null);
        })
        .error(err -> {
          hidePreview();
          return null;
        });
  }

  private void hidePreview() {
    lastHoverKey = null;
    previewPopup.hide();
  }
}
